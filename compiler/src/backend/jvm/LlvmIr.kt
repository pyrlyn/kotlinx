package newkotlin.jvm

import newkotlin.CompileError

/*
 * Textual LLVM IR (as printed by `opt -S`) -> a small in-memory model for the JVM backend.
 * Only the subset that `opt` leaves in a KotlinX program after `internalize,default<O2>` is modelled:
 * scalar integer/float/pointer code, structs/arrays in memory, phi/select/switch, direct and indirect calls,
 * intrinsics, `{ iN, i1 }` aggregates (overflow intrinsics, cmpxchg). Anything else is a CompileError naming it.
 */

// ---------------------------------------------------------------- types

sealed class LType
object VoidT : LType() { override fun toString() = "void" }
object LabelT : LType() { override fun toString() = "label" }
object MetaT : LType() { override fun toString() = "metadata" }
object TokenT : LType() { override fun toString() = "token" }
object PtrT : LType() { override fun toString() = "ptr" }
object FloatT : LType() { override fun toString() = "float" }
object DoubleT : LType() { override fun toString() = "double" }
class IntT(val bits: Int) : LType() { override fun toString() = "i$bits" }
class StructT(val fields: List<LType>, val packed: Boolean) : LType() {
    override fun toString() = fields.joinToString(", ", if (packed) "<{ " else "{ ", if (packed) " }>" else " }")
}
class ArrayT(val n: Long, val elem: LType) : LType() { override fun toString() = "[$n x $elem]" }
class VecT(val n: Int, val elem: LType) : LType() { override fun toString() = "<$n x $elem>" }
class FuncT(val ret: LType, val params: List<LType>, val varargs: Boolean) : LType() {
    override fun toString() = "$ret (${params.joinToString(", ")}${if (varargs) ", ..." else ""})"
}
/** `%struct.X`: resolved through the module's type table (definitions may follow their uses). */
class NamedT(val name: String, private val table: Map<String, LType>) : LType() {
    val resolved: LType get() = table[name] ?: throw CompileError("jvm: unknown LLVM type %$name")
    override fun toString() = "%$name"
}

fun LType.r(): LType = if (this is NamedT) resolved.r() else this

// ---------------------------------------------------------------- values

sealed class LValue
class Local(val name: String) : LValue() { override fun toString() = "%$name" }
class GlobalRef(val name: String) : LValue() { override fun toString() = "@$name" }
class CInt(val v: Long) : LValue() { override fun toString() = "$v" }
class CFloat(val v: Double) : LValue() { override fun toString() = "$v" }
/** null / zeroinitializer / undef / poison: all read as zero bits. */
object CZero : LValue() { override fun toString() = "zero" }
class CAgg(val elems: List<TV>) : LValue()                    // struct / array / vector constant
class CBytes(val bytes: ByteArray) : LValue()                  // c"..."
/** A constant expression; [pred] is the predicate of an `icmp`. */
class CExpr(val op: String, val args: List<TV>, val to: LType? = null, val srcElem: LType? = null, val pred: String? = null) : LValue() {
    override fun toString() = "$op(${args.joinToString()})"
}

/** A typed operand. */
class TV(val t: LType, val v: LValue) { override fun toString() = "$t $v" }

// ---------------------------------------------------------------- instructions, functions, module

class Inst(val res: String?, val op: String) {
    var ty: LType = VoidT                    // result type (load/alloca/cast: the loaded/allocated/destination type)
    val ops = ArrayList<TV>()                 // operands in source order
    var pred = ""                             // icmp/fcmp predicate, atomicrmw operation
    val labels = ArrayList<String>()          // br targets (cond: true, false), switch default
    val cases = ArrayList<Pair<Long, String>>()
    val incoming = ArrayList<Pair<LValue, String>>()  // phi
    var srcElem: LType? = null                // gep source element type, alloca element type
    var indices: IntArray = IntArray(0)       // extractvalue / insertvalue
    var callee: LValue? = null
    var fnTy: FuncT? = null
    var line = 0
    override fun toString() = "${res?.let { "%$it = " } ?: ""}$op $ty ${ops.joinToString()}"
}

class Block(val name: String) { val insts = ArrayList<Inst>() }

class LFunc(val name: String, val ret: LType, val params: List<LType>, val paramNames: List<String>, val varargs: Boolean) {
    val blocks = ArrayList<Block>()
    val isDecl get() = blocks.isEmpty()
    val type get() = FuncT(ret, params, varargs)
}

class LGlobal(val name: String, val ty: LType, val init: LValue?, val isConst: Boolean, val align: Int)

class LModule {
    val types = HashMap<String, LType>()
    val globals = LinkedHashMap<String, LGlobal>()
    val funcs = LinkedHashMap<String, LFunc>()
    val aliases = HashMap<String, LValue>()
}

// ---------------------------------------------------------------- lexer

enum class TK { WORD, LOCAL, GLOBAL, META, ATTR, INT, FLOAT, HEX, STR, CSTR, LABEL, PUNCT, EOF }
class Tok(val k: TK, val s: String, val line: Int) { override fun toString() = "$k '$s' (line $line)" }

private fun isNameChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '.' || c == '$' || c == '-'

private fun unescape(s: String): ByteArray {
    val out = ArrayList<Byte>(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length) {
            if (s[i + 1] == '\\') { out.add('\\'.code.toByte()); i += 2; continue }
            out.add(s.substring(i + 1, i + 3).toInt(16).toByte()); i += 3; continue
        }
        // IR text is UTF-8: re-encode non-ASCII characters
        if (c.code < 0x80) { out.add(c.code.toByte()); i++ } else { for (b in c.toString().encodeToByteArray()) out.add(b); i++ }
    }
    return out.toByteArray()
}

fun lexIr(src: String): List<Tok> {
    val toks = ArrayList<Tok>(src.length / 4)
    var i = 0; var line = 1; val n = src.length
    fun quoted(): String { // at opening quote
        val sb = StringBuilder(); i++
        while (i < n && src[i] != '"') { sb.append(src[i]); i++ }
        i++; return sb.toString()
    }
    fun name(): String = if (i < n && src[i] == '"') unescape(quoted()).map { (it.toInt() and 0xFF).toChar() }.toCharArray().concatToString()
        else { val s = i; while (i < n && isNameChar(src[i])) i++; src.substring(s, i) }
    while (i < n) {
        val c = src[i]
        when {
            c == '\n' -> { line++; i++ }
            c == ' ' || c == '\t' || c == '\r' -> i++
            c == ';' -> { while (i < n && src[i] != '\n') i++ }
            c == '%' -> { i++; toks.add(Tok(TK.LOCAL, name(), line)) }
            c == '@' -> { i++; toks.add(Tok(TK.GLOBAL, name(), line)) }
            c == '!' -> { i++; toks.add(Tok(TK.META, if (i < n && src[i] == '"') quoted() else name(), line)) }
            c == '#' -> { i++; val s = i; while (i < n && src[i].isDigit()) i++; toks.add(Tok(TK.ATTR, src.substring(s, i), line)) }
            c == '"' -> { val s = quoted(); if (i < n && src[i] == ':') { i++; toks.add(Tok(TK.LABEL, s, line)) } else toks.add(Tok(TK.STR, s, line)) }
            c == 'c' && i + 1 < n && src[i + 1] == '"' -> { i++; toks.add(Tok(TK.CSTR, quoted(), line)) }
            c == '.' && src.startsWith("...", i) -> { toks.add(Tok(TK.PUNCT, "...", line)); i += 3 }
            c.isDigit() || (c == '-' && i + 1 < n && (src[i + 1].isDigit())) || (c == '+' && i + 1 < n && src[i + 1].isDigit()) -> {
                val s = i
                if (c == '0' && i + 1 < n && src[i + 1] == 'x') {
                    i += 2; while (i < n && (src[i].isLetterOrDigit())) i++
                    toks.add(Tok(TK.HEX, src.substring(s, i), line)); continue
                }
                i++
                var fl = false
                while (i < n && (src[i].isDigit() || src[i] == '.' || src[i] == 'e' || src[i] == 'E' ||
                            ((src[i] == '+' || src[i] == '-') && (src[i - 1] == 'e' || src[i - 1] == 'E')))) {
                    if (!src[i].isDigit()) fl = true; i++
                }
                if (!fl && i < n && src[i] == ':') { i++; toks.add(Tok(TK.LABEL, src.substring(s, i - 1), line)); continue }
                if (!fl && i < n && isNameChar(src[i])) { // a label/name starting with digits, e.g. `5abc:` (rare)
                    while (i < n && isNameChar(src[i])) i++
                    if (i < n && src[i] == ':') { i++; toks.add(Tok(TK.LABEL, src.substring(s, i - 1), line)) } else toks.add(Tok(TK.WORD, src.substring(s, i), line))
                    continue
                }
                toks.add(Tok(if (fl) TK.FLOAT else TK.INT, src.substring(s, i), line))
            }
            c.isLetter() || c == '_' || c == '$' || c == '.' -> {
                val s = i
                while (i < n && isNameChar(src[i])) i++
                val w = src.substring(s, i)
                if (i < n && src[i] == ':' && !(i + 1 < n && src[i + 1] == ':')) { i++; toks.add(Tok(TK.LABEL, w, line)) }
                else if (w.startsWith("f0x") && w.length > 3) toks.add(Tok(TK.HEX, w, line)) // LLVM 22+ float literal: the bits at the type's width
                else toks.add(Tok(TK.WORD, w, line))
            }
            else -> { toks.add(Tok(TK.PUNCT, c.toString(), line)); i++ }
        }
    }
    toks.add(Tok(TK.EOF, "", line + 1))
    return toks
}

// ---------------------------------------------------------------- parser

private val LINKAGE = setOf("private", "internal", "external", "weak", "weak_odr", "linkonce", "linkonce_odr", "common",
    "appending", "extern_weak", "available_externally", "dso_local", "dso_preemptable", "hidden", "protected", "default",
    "unnamed_addr", "local_unnamed_addr", "externally_initialized", "thread_local", "dllimport", "dllexport",
    "fastcc", "ccc", "coldcc", "tailcc", "swiftcc", "preserve_mostcc", "preserve_allcc", "cfguard_checkcc",
    "aarch64_vector_pcs", "aarch64_sve_vector_pcs")

/** Parameter / return attributes (skipped). Those taking a parenthesized argument are in ATTR_ARG. */
private val PARAM_ATTRS = setOf("noundef", "nonnull", "noalias", "nocapture", "readonly", "readnone", "writeonly", "zeroext",
    "signext", "inreg", "returned", "nofree", "nest", "swiftself", "swifterror", "immarg", "noext", "writable",
    "dead_on_unwind", "dead_on_return", "allocptr", "allocalign", "nonlazybind", "noinline", "alwaysinline", "noreturn", "nounwind",
    "cold", "hot", "willreturn", "mustprogress", "speculatable", "norecurse", "nosync", "inlinehint", "optsize", "minsize",
    "builtin", "nobuiltin", "convergent", "uwtable", "ssp", "sspstrong", "sspreq", "returns_twice", "nomerge", "strictfp")
private val ATTR_ARG = setOf("align", "dereferenceable", "dereferenceable_or_null", "range", "captures", "byval", "sret",
    "byref", "preallocated", "inalloca", "elementtype", "initializes", "nofpclass", "alignstack", "memory", "allocsize",
    "allockind", "vscale_range", "uwtable", "dead_on_return")
private val INST_FLAGS = setOf("nuw", "nsw", "exact", "disjoint", "samesign", "nneg", "fast", "nnan", "ninf", "nsz", "arcp",
    "contract", "afn", "reassoc", "inbounds", "nusw", "volatile", "atomic", "tail", "musttail", "notail")
private val ORDERINGS = setOf("unordered", "monotonic", "acquire", "release", "acq_rel", "seq_cst")
private val CASTS = setOf("trunc", "zext", "sext", "fptrunc", "fpext", "fptoui", "fptosi", "uitofp", "sitofp", "ptrtoint",
    "inttoptr", "bitcast", "addrspacecast")
private val BINOPS = setOf("add", "sub", "mul", "udiv", "sdiv", "urem", "srem", "shl", "lshr", "ashr", "and", "or", "xor",
    "fadd", "fsub", "fmul", "fdiv", "frem")

class IrParser(src: String) {
    private val toks = lexIr(src)
    private var p = 0
    val m = LModule()

    private val t get() = toks[p]
    private fun peek(k: Int = 1) = toks[minOf(p + k, toks.size - 1)]
    private fun err(msg: String): Nothing = throw CompileError("jvm: LLVM IR line ${t.line}: $msg (at ${t.s.take(40)})")
    private fun isP(s: String) = t.k == TK.PUNCT && t.s == s
    private fun isW(s: String) = t.k == TK.WORD && t.s == s
    private fun expectP(s: String) { if (!isP(s)) err("expected '$s'"); p++ }
    private fun eatP(s: String): Boolean { if (isP(s)) { p++; return true }; return false }
    private fun eatW(s: String): Boolean { if (isW(s)) { p++; return true }; return false }
    private fun word(): String { if (t.k != TK.WORD) err("expected a keyword"); return toks[p++].s }
    private fun skipLine() { val l = t.line; while (t.k != TK.EOF && t.line == l) p++ }
    /** Skips a balanced (...) group if present. */
    private fun skipParens() {
        if (!isP("(")) return
        var d = 0
        do { if (isP("(")) d++ else if (isP(")")) d--; p++ } while (d > 0 && t.k != TK.EOF)
    }

    fun parse(): LModule {
        while (t.k != TK.EOF) {
            when {
                t.k == TK.LOCAL && peek().s == "=" && peek(2).s == "type" -> {
                    val name = t.s; p += 3
                    if (eatW("opaque")) m.types[name] = StructT(emptyList(), false) else m.types[name] = type()
                }
                t.k == TK.GLOBAL && peek().s == "=" -> global()
                isW("define") -> function(true)
                isW("declare") -> function(false)
                else -> skipLine() // source_filename, target, attributes #N, metadata, module asm, comdat
            }
        }
        return m
    }

    // ---- types

    fun type(): LType {
        var ty: LType = when {
            t.k == TK.WORD -> {
                val w = word()
                when {
                    w == "void" -> VoidT; w == "ptr" -> PtrT; w == "float" -> FloatT; w == "double" -> DoubleT
                    w == "label" -> LabelT; w == "metadata" -> MetaT; w == "token" -> TokenT
                    w.length > 1 && w[0] == 'i' && w.substring(1).all { it.isDigit() } -> IntT(w.substring(1).toInt())
                    else -> err("unsupported type $w")
                }
            }
            t.k == TK.LOCAL -> NamedT(toks[p++].s, m.types)
            isP("{") -> { p++; structBody(false) }
            isP("<") && peek().s == "{" -> { p += 2; val s = structBody(true); expectP(">"); s }
            isP("[") -> { p++; val n = toks[p++].s.toLong(); if (word() != "x") err("expected x"); val e = type(); expectP("]"); ArrayT(n, e) }
            isP("<") -> { p++; val n = toks[p++].s.toInt(); if (word() != "x") err("expected x"); val e = type(); expectP(">"); VecT(n, e) }
            else -> err("expected a type")
        }
        if (ty === PtrT && isW("addrspace")) { p++; skipParens() }
        while (isP("(") && ty !== LabelT) { // function type
            p++
            val ps = ArrayList<LType>(); var va = false
            while (!isP(")")) {
                if (eatP("...")) { va = true; continue }
                ps.add(type()); skipParamAttrs(); eatP(",")
            }
            p++
            ty = FuncT(ty, ps, va)
            if (isP("*")) p++
        }
        return ty
    }

    private fun structBody(packed: Boolean): StructT {
        val fs = ArrayList<LType>()
        while (!isP("}")) { fs.add(type()); eatP(",") }
        p++
        return StructT(fs, packed)
    }

    private fun skipParamAttrs() {
        while (t.k == TK.WORD) {
            val w = t.s
            if (w in ATTR_ARG) { p++; if (w == "align" && t.k == TK.INT) p++ else skipParens() }
            else if (w in PARAM_ATTRS) p++
            else break
        }
    }

    // ---- constants and operands

    /** An operand of type [ty] (constants, locals, globals, constant expressions). */
    fun value(ty: LType): LValue {
        val tk = t
        return when (tk.k) {
            TK.LOCAL -> { p++; Local(tk.s) }
            TK.GLOBAL -> { p++; GlobalRef(tk.s) }
            TK.INT -> { p++; CInt(tk.s.toLongOrNull() ?: tk.s.toULong().toLong()) }
            TK.FLOAT -> { p++; CFloat(tk.s.toDouble()) }
            TK.HEX -> { p++; hexConst(tk.s, ty) }
            TK.CSTR -> { p++; CBytes(unescape(tk.s)) }
            TK.PUNCT -> when (tk.s) {
                "{" -> { p++; val es = ArrayList<TV>(); while (!isP("}")) { es.add(typedValue()); eatP(",") }; p++; CAgg(es) }
                "[" -> { p++; val es = ArrayList<TV>(); while (!isP("]")) { es.add(typedValue()); eatP(",") }; p++; CAgg(es) }
                "<" -> {
                    p++
                    val packed = eatP("{")
                    val es = ArrayList<TV>()
                    while (!isP(if (packed) "}" else ">")) { es.add(typedValue()); eatP(",") }
                    p++; if (packed) expectP(">")
                    CAgg(es)
                }
                else -> err("unexpected operand")
            }
            TK.WORD -> when (val w = tk.s) {
                "null", "undef", "poison", "zeroinitializer", "none" -> { p++; CZero }
                "true" -> { p++; CInt(1) }
                "false" -> { p++; CInt(0) }
                "dso_local_equivalent", "no_cfi" -> { p++; value(ty) }
                "getelementptr" -> {
                    p++
                    while (t.k == TK.WORD && (t.s in INST_FLAGS || t.s == "inrange")) { val f = word(); if (f == "inrange") skipParens() }
                    expectP("(")
                    val se = type(); expectP(",")
                    val args = ArrayList<TV>()
                    while (!isP(")")) { while (isW("inrange")) { p++; skipParens() }; args.add(typedValue()); eatP(",") }
                    p++
                    CExpr("getelementptr", args, srcElem = se)
                }
                in CASTS -> { p++; expectP("("); val a = typedValue(); if (word() != "to") err("expected to"); val to = type(); expectP(")"); CExpr(w, listOf(a), to) }
                in BINOPS -> {
                    p++; while (t.k == TK.WORD && t.s in INST_FLAGS) p++
                    expectP("("); val a = typedValue(); expectP(","); val b = typedValue(); expectP(")")
                    CExpr(w, listOf(a, b))
                }
                // LLVM 18 and older (removed in 19), e.g. the addresses of two globals compared
                "icmp" -> {
                    p++; val pred = word()
                    expectP("("); val a = typedValue(); expectP(","); val b = typedValue(); expectP(")")
                    CExpr(w, listOf(a, b), pred = pred)
                }
                else -> err("unsupported constant '$w'")
            }
            else -> err("unexpected operand")
        }
    }

    private fun hexConst(s: String, ty: LType): LValue {
        val body = s.substringAfter("0x")
        if (body[0] in "KLMHR") err("unsupported floating-point literal $s")
        val bits = body.toULong(16).toLong()
        if (s.startsWith("f") && ty.r() == FloatT) return CFloat(Float.fromBits(bits.toInt()).toDouble())
        return when (ty.r()) {
            DoubleT, FloatT -> CFloat(Double.fromBits(bits)) // float literals are printed as the equivalent double
            else -> CInt(bits)
        }
    }

    fun typedValue(): TV { val ty = type(); skipParamAttrs(); return TV(ty, value(ty)) }

    // ---- globals

    private fun global() {
        val name = t.s; p += 2
        if (isW("alias") || (t.k == TK.WORD && peekAlias())) { // @a = [linkage] alias T, ptr @b
            while (!isW("alias")) p++
            p++; type(); expectP(","); val tv = typedValue(); m.aliases[name] = tv.v; skipLine(); return
        }
        while (t.k == TK.WORD && (t.s in LINKAGE || t.s == "addrspace")) { if (t.s == "addrspace") { p++; skipParens() } else p++ }
        val kind = word()
        if (kind != "global" && kind != "constant") err("unsupported global kind $kind")
        val ty = type()
        val external = toks.subList(0, p).asReversed().take(12).any { it.s == "external" || it.s == "extern_weak" } && toks[p].line != toks[p - 1].line + 1 &&
            (t.k == TK.EOF || t.line != toks[p - 1].line || isP(","))
        val init = if (external) null else value(ty)
        var align = 0
        while (t.k != TK.EOF && t.line == toks[p - 1].line && isP(",")) {
            p++
            when {
                isW("align") -> { p++; align = t.s.toInt(); p++ }
                isW("section") || isW("partition") -> { p++; p++ }
                isW("comdat") -> { p++; skipParens() }
                else -> { skipLine(); break }
            }
        }
        m.globals[name] = LGlobal(name, ty, init, kind == "constant", align)
    }

    private fun peekAlias(): Boolean {
        var k = p
        while (toks[k].k == TK.WORD && toks[k].s in LINKAGE) k++
        return toks[k].k == TK.WORD && toks[k].s == "alias"
    }

    // ---- functions

    private fun function(define: Boolean) {
        p++
        while (t.k == TK.WORD && (t.s in LINKAGE || t.s in PARAM_ATTRS || t.s in ATTR_ARG || t.s == "cc")) {
            val w = word(); if (w in ATTR_ARG) { if (w == "align") p++ else skipParens() } else if (w == "cc") p++
        }
        val ret = type()
        skipParamAttrs()
        if (t.k != TK.GLOBAL) err("expected function name")
        val name = toks[p++].s
        expectP("(")
        val ps = ArrayList<LType>(); val names = ArrayList<String>(); var va = false
        var unnamed = 0 // numbered values: unnamed arguments, then the unnamed entry block
        while (!isP(")")) {
            if (eatP("...")) { va = true; continue }
            ps.add(type()); skipParamAttrs()
            if (t.k == TK.LOCAL && peek().let { it.s == "," || it.s == ")" }) {
                val nm = toks[p++].s; names.add(nm); if (nm.all { it.isDigit() }) unnamed = nm.toInt() + 1
            } else names.add((unnamed++).toString())
            eatP(",")
        }
        p++
        val f = LFunc(name, ret, ps, names, va)
        m.funcs[name] = f
        if (!define) { skipLine(); return }
        while (!isP("{")) { if (t.k == TK.EOF) err("missing function body"); p++ }
        p++
        // Unnamed entry block: its implicit number follows the last numbered argument.
        var cur = Block(unnamed.toString())
        f.blocks.add(cur)
        while (!isP("}")) {
            if (t.k == TK.LABEL) {
                val b = Block(toks[p++].s)
                if (cur.insts.isEmpty() && cur === f.blocks[0] && f.blocks.size == 1) { f.blocks[0] = b } else f.blocks.add(b)
                cur = b; continue
            }
            cur.insts.add(inst())
        }
        p++
    }

    // ---- instructions

    private fun inst(): Inst {
        val line = t.line
        var res: String? = null
        if (t.k == TK.LOCAL && peek().s == "=") { res = t.s; p += 2 }
        while (isW("tail") || isW("musttail") || isW("notail")) p++
        val op = word()
        val i = Inst(res, op); i.line = line
        when (op) {
            "ret" -> { if (isW("void")) p++ else i.ops.add(typedValue()) }
            "br" -> {
                if (isW("label")) { p++; i.labels.add(toks[p++].s) }
                else { i.ops.add(typedValue()); expectP(","); p++; i.labels.add(toks[p++].s); expectP(","); p++; i.labels.add(toks[p++].s) }
            }
            "switch" -> {
                i.ops.add(typedValue()); expectP(","); p++; i.labels.add(toks[p++].s)
                expectP("[")
                while (!isP("]")) { type(); val v = value(IntT(64)) as CInt; expectP(","); p++; i.cases.add(v.v to toks[p++].s) }
                p++
            }
            "unreachable" -> {}
            "fneg" -> { while (t.k == TK.WORD && t.s in INST_FLAGS) p++; val a = typedValue(); i.ty = a.t; i.ops.add(a) }
            in BINOPS -> {
                while (t.k == TK.WORD && t.s in INST_FLAGS) p++
                val ty = type(); val a = value(ty); expectP(","); val b = value(ty)
                i.ty = ty; i.ops.add(TV(ty, a)); i.ops.add(TV(ty, b))
            }
            "icmp", "fcmp" -> {
                while (t.k == TK.WORD && t.s in INST_FLAGS) p++
                i.pred = word()
                val ty = type(); val a = value(ty); expectP(","); val b = value(ty)
                i.ty = if (ty.r() is VecT) err("vector compare") else IntT(1); i.ops.add(TV(ty, a)); i.ops.add(TV(ty, b))
            }
            "phi" -> {
                while (t.k == TK.WORD && t.s in INST_FLAGS) p++
                i.ty = type()
                while (isP("[")) {
                    p++; val v = value(i.ty); expectP(","); val l = toks[p++].s; expectP("]")
                    i.incoming.add(v to l)
                    if (!eatP(",")) break
                    if (!isP("[")) { p--; break } // `, !md` follows
                }
            }
            "select" -> {
                while (t.k == TK.WORD && t.s in INST_FLAGS) p++
                val c = typedValue(); expectP(","); val a = typedValue(); expectP(","); val b = typedValue()
                i.ty = a.t; i.ops.add(c); i.ops.add(a); i.ops.add(b)
            }
            "call" -> call(i)
            "alloca" -> {
                eatW("inalloca")
                i.srcElem = type(); i.ty = PtrT
                if (eatP(",") && !isW("align") && !isW("addrspace")) i.ops.add(typedValue())
                else if (t.line == line && !isP(",")) p--
            }
            "load" -> {
                val atomic = eatW("atomic"); eatW("volatile")
                i.ty = type(); expectP(","); i.ops.add(typedValue())
                if (atomic) { while (isW("syncscope")) { p++; skipParens() }; if (t.k == TK.WORD && t.s in ORDERINGS) p++ }
            }
            "store" -> {
                val atomic = eatW("atomic"); eatW("volatile")
                i.ops.add(typedValue()); expectP(","); i.ops.add(typedValue())
                if (atomic) { while (isW("syncscope")) { p++; skipParens() }; if (t.k == TK.WORD && t.s in ORDERINGS) p++ }
            }
            "getelementptr" -> {
                while (t.k == TK.WORD && (t.s in INST_FLAGS || t.s == "inrange")) { val f = word(); if (f == "inrange") skipParens() }
                i.srcElem = type(); expectP(",")
                i.ops.add(typedValue())
                while (isP(",") && peek().k != TK.META) { p++; i.ops.add(typedValue()) }
                i.ty = PtrT
            }
            in CASTS -> {
                while (t.k == TK.WORD && t.s in INST_FLAGS) p++
                i.ops.add(typedValue()); if (word() != "to") err("expected to"); i.ty = type()
            }
            "freeze" -> { val a = typedValue(); i.ty = a.t; i.ops.add(a) }
            "extractvalue" -> {
                val a = typedValue(); i.ops.add(a)
                val idx = ArrayList<Int>(); while (isP(",") && peek().k == TK.INT) { p++; idx.add(toks[p++].s.toInt()) }
                i.indices = idx.toIntArray()
                var ty = a.t.r(); for (k in idx) ty = when (ty) { is StructT -> ty.fields[k].r(); is ArrayT -> ty.elem.r(); else -> err("bad extractvalue") }
                i.ty = ty
            }
            "insertvalue" -> {
                val a = typedValue(); expectP(","); val b = typedValue(); i.ops.add(a); i.ops.add(b)
                val idx = ArrayList<Int>(); while (isP(",") && peek().k == TK.INT) { p++; idx.add(toks[p++].s.toInt()) }
                i.indices = idx.toIntArray(); i.ty = a.t
            }
            "atomicrmw" -> {
                eatW("volatile"); i.pred = word()
                i.ops.add(typedValue()); expectP(","); val v = typedValue(); i.ops.add(v); i.ty = v.t
            }
            "cmpxchg" -> {
                eatW("weak"); eatW("volatile")
                i.ops.add(typedValue()); expectP(","); val a = typedValue(); expectP(","); val b = typedValue()
                i.ops.add(a); i.ops.add(b); i.ty = StructT(listOf(a.t, IntT(1)), false)
            }
            "fence" -> {}
            else -> err("unsupported instruction '$op'")
        }
        // Trailing metadata, alignment, orderings: the rest of the line (switch/phi lines end where parsed).
        if (t.line == line || (toks[p - 1].line == t.line)) skipToLineEnd(toks[p - 1].line)
        return i
    }

    private fun skipToLineEnd(l: Int) { while (t.k != TK.EOF && t.line == l && !(t.k == TK.LABEL) && !isP("}")) p++ }

    private fun call(i: Inst) {
        while (t.k == TK.WORD && (t.s in INST_FLAGS || t.s in LINKAGE || t.s in PARAM_ATTRS || t.s in ATTR_ARG || t.s == "cc")) {
            val w = word(); if (w in ATTR_ARG) { if (w == "align") p++ else skipParens() } else if (w == "cc") p++
        }
        var ty = type()
        skipParamAttrs()
        val callee = value(PtrT)
        expectP("(")
        val args = ArrayList<TV>()
        while (!isP(")")) {
            val at = type(); skipParamAttrs()
            args.add(TV(at, if (at === MetaT) { skipMetaArg(); CZero } else value(at)))
            eatP(",")
        }
        p++
        val fnTy = ty as? FuncT ?: FuncT(ty, args.map { it.t }, false)
        i.fnTy = fnTy; i.ty = fnTy.ret; i.callee = callee; i.ops.addAll(args)
        // function attributes `#N`, operand bundles `[ "..."(...) ]`
        while (t.k == TK.ATTR) p++
        if (isP("[")) { var d = 0; do { if (isP("[")) d++ else if (isP("]")) d--; p++ } while (d > 0) }
    }

    private fun skipMetaArg() { // metadata argument of a debug intrinsic: !N, !{...}, !DIExpression(...)
        p++
        if (isP("(")) skipParens() else if (isP("{")) { var d = 0; do { if (isP("{")) d++ else if (isP("}")) d--; p++ } while (d > 0) }
    }
}

fun parseLlvmIr(src: String): LModule = IrParser(src).parse()

// ---------------------------------------------------------------- data layout (64-bit, little endian; AArch64/x86-64)

fun alignOf(t: LType): Int = when (val r = t.r()) {
    is IntT -> when { r.bits <= 8 -> 1; r.bits <= 16 -> 2; r.bits <= 32 -> 4; r.bits <= 64 -> 8; else -> 16 }
    PtrT -> 8; FloatT -> 4; DoubleT -> 8
    is StructT -> if (r.packed) 1 else r.fields.maxOfOrNull { alignOf(it) } ?: 1
    is ArrayT -> alignOf(r.elem)
    is VecT -> { var a = 1; val s = sizeOf(r.elem) * r.n; while (a < s) a *= 2; minOf(a, 16).toInt() }
    else -> throw CompileError("jvm: no alignment for type $r")
}

fun sizeOf(t: LType): Long = when (val r = t.r()) {
    is IntT -> { val s = ((r.bits + 7) / 8).toLong(); val a = alignOf(r).toLong(); (s + a - 1) / a * a }
    PtrT -> 8; FloatT -> 4; DoubleT -> 8
    is StructT -> { var off = 0L; for (f in r.fields) { if (!r.packed) off = alignUp(off, alignOf(f)); off += sizeOf(f) }; alignUp(off, alignOf(r)) }
    is ArrayT -> r.n * sizeOf(r.elem)
    is VecT -> alignUp(sizeOf(r.elem) * r.n, alignOf(r))
    else -> throw CompileError("jvm: no size for type $r")
}

fun alignUp(v: Long, a: Int): Long = (v + a - 1) / a * a

fun fieldOffset(s: StructT, idx: Int): Long {
    var off = 0L
    for (k in 0 until idx) { if (!s.packed) off = alignUp(off, alignOf(s.fields[k])); off += sizeOf(s.fields[k]) }
    if (!s.packed) off = alignUp(off, alignOf(s.fields[idx]))
    return off
}
