package newkotlin

/*
 * Reader for the textual LLVM IR that `opt` prints (the subset an optimized, linked KotlinX program uses:
 * no vectors, no EH, no varargs definitions). Platform independent, so the JS backend can be developed and
 * tested on the JVM too. Model: LlModule (types, globals, functions), each function a list of LlBlocks of LlInsts.
 */

sealed class LTy
object LVoid : LTy() { override fun toString() = "void" }
object LPtr : LTy() { override fun toString() = "ptr" }
object LFloat : LTy() { override fun toString() = "float" }
object LDouble : LTy() { override fun toString() = "double" }
object LLabel : LTy() { override fun toString() = "label" }
object LOther : LTy() { override fun toString() = "other" } // token, metadata
data class LInt(val bits: Int) : LTy() { override fun toString() = "i$bits" }
data class LArr(val n: Long, val elem: LTy) : LTy() { override fun toString() = "[$n x $elem]" }
data class LVec(val n: Long, val elem: LTy) : LTy() { override fun toString() = "<$n x $elem>" }
class LStruct(var fields: List<LTy>, val packed: Boolean, val name: String? = null) : LTy() {
    override fun toString() = name ?: fields.joinToString(", ", if (packed) "<{" else "{", if (packed) "}>" else "}")
}
data class LFn(val ret: LTy, val params: List<LTy>, val varargs: Boolean) : LTy()

sealed class LV
data class VLocal(val name: String) : LV()
data class VGlobal(val name: String) : LV()
data class VInt(val v: Long) : LV()
data class VFloat(val v: Double) : LV()
object VNull : LV()
object VUndef : LV()
object VZero : LV()
class VStr(val bytes: ByteArray) : LV()
class VAgg(val elems: List<Pair<LTy, LV>>) : LV()
/** Constant expression: op is a cast name, a binop name, or "gep" (srcTy, then (ty, value) operands). */
class VExpr(val op: String, val ty: LTy, val ops: List<Pair<LTy, LV>>, val srcTy: LTy? = null) : LV()

class LlInst(
    val res: String?, val op: String, val ty: LTy,
    val ops: List<Pair<LTy, LV>> = emptyList(),
) {
    var pred = ""              // icmp/fcmp predicate, cast target in ty
    var labels: List<String> = emptyList()   // br / switch targets (switch: default first)
    var cases: List<Long> = emptyList()      // switch case values
    var incoming: List<Pair<LV, String>> = emptyList() // phi
    var srcTy: LTy = LVoid     // gep source element type, alloca type, load type
    var align = 0
    var argAlign: List<Int> = emptyList()    // call: `align N` attribute of each argument (0 if none)
    var idx: List<Int> = emptyList()         // extractvalue/insertvalue indices
    var callee: LV? = null
    var fnTy: LFn? = null
    var flag = ""              // atomicrmw operation
}

class LlBlock(val name: String) { val insts = ArrayList<LlInst>() }

class LlParam(val ty: LTy, val name: String)

class LlFunc(val name: String, val ret: LTy, val params: List<LlParam>, val varargs: Boolean) {
    val blocks = ArrayList<LlBlock>()
    val isDecl get() = blocks.isEmpty()
}

class LlGlobal(val name: String, val ty: LTy, val init: LV?, val align: Int, val isConst: Boolean)

class LlModule {
    val types = HashMap<String, LStruct>()
    val globals = LinkedHashMap<String, LlGlobal>()
    val funcs = LinkedHashMap<String, LlFunc>()
    val aliases = HashMap<String, LV>()
}

private class LlTok(val k: Char, val s: String) {
    // k: 'w' word, '%' local, '@' global, 'n' number, 'c' c-string (s = raw escaped body), 's' string,
    //    '!' metadata, '#' attribute group, 'p' punctuation, '.' ellipsis
    override fun toString() = "$k:$s"
}

class LlParser(private val text: String) {
    private val m = LlModule()
    private var toks: List<LlTok> = emptyList()
    private var p = 0
    private var lineNo = 0

    private fun err(msg: String): Nothing = throw CompileError("LLVM IR line $lineNo: $msg (at ${toks.drop(p).take(6).joinToString(" ") { it.s }})")

    fun parse(): LlModule {
        val lines = text.split('\n')
        var i = 0
        // Named types first: globals and functions refer to them.
        for (l in lines) {
            val t = l.trimStart()
            if (t.startsWith("%") && " = type " in t) m.types[unq(t.substring(1, t.indexOf(" = type ")))] = LStruct(emptyList(), false, t.substring(0, t.indexOf(" = type ")))
        }
        var fn: LlFunc? = null
        var blk: LlBlock? = null
        while (i < lines.size) {
            var line = lines[i]; lineNo = ++i
            if (line.trimStart().startsWith("#dbg_")) continue // debug records
            if (fn != null && line.trimEnd().endsWith("[") && line.trimStart().let { it.startsWith("switch") || " switch " in it }) {
                val sb = StringBuilder(line)
                while (i < lines.size) { val l2 = lines[i]; lineNo = ++i; sb.append(' ').append(l2.trim()); if (l2.trim().startsWith("]")) break }
                line = sb.toString()
            }
            toks = lex(line); p = 0
            if (toks.isEmpty()) continue
            val t0 = toks[0]
            if (fn != null) {
                if (t0.k == 'p' && t0.s == "}") { fn = null; blk = null; continue }
                if (toks.size >= 2 && toks[1].s == ":" && toks[1].k == 'p' && (t0.k == 'w' || t0.k == 'n' || t0.k == 's')) {
                    blk = LlBlock(t0.s); fn.blocks.add(blk); continue
                }
                if (blk == null) { blk = LlBlock(fn.params.count { q -> q.name.all { c -> c.isDigit() } }.toString()); fn.blocks.add(blk) } // unnamed entry: next number
                blk.insts.add(inst()); continue
            }
            when {
                t0.k == '%' && toks.size > 2 && toks[2].s == "type" -> namedType()
                t0.k == '@' -> global()
                t0.k == 'w' && t0.s == "define" -> fn = func(true)
                t0.k == 'w' && t0.s == "declare" -> func(false)
                else -> {} // target, source_filename, attributes, metadata, comdat
            }
        }
        return m
    }

    // ---- lexer -------------------------------------------------------------------------------------
    private fun lex(line: String): List<LlTok> {
        val out = ArrayList<LlTok>()
        var j = 0; val n = line.length
        fun ident(start: Int): Int { var e = start; while (e < n && (line[e].isLetterOrDigit() || line[e] in "._$-")) e++; return e }
        fun quoted(start: Int): Int { var e = start + 1; while (e < n && line[e] != '"') e++; return e + 1 }
        while (j < n) {
            val c = line[j]
            when {
                c == ' ' || c == '\t' -> j++
                c == ';' -> j = n
                c == '%' || c == '@' -> {
                    val e = if (j + 1 < n && line[j + 1] == '"') quoted(j + 1) else ident(j + 1)
                    out.add(LlTok(c, unq(line.substring(j + 1, e)))); j = e
                }
                c == '!' -> {
                    if (j + 1 < n && line[j + 1] == '{') { out.add(LlTok('!', "{")); j += 2; var d = 1; while (j < n && d > 0) { if (line[j] == '{') d++; if (line[j] == '}') d--; j++ } }
                    else if (j + 1 < n && line[j + 1] == '"') { val e = quoted(j + 1); out.add(LlTok('!', line.substring(j, e))); j = e }
                    else { val e = ident(j + 1); out.add(LlTok('!', line.substring(j, e))); j = e }
                }
                c == '#' -> { val e = ident(j + 1); out.add(LlTok('#', line.substring(j + 1, e))); j = e }
                c == 'c' && j + 1 < n && line[j + 1] == '"' -> { val e = quoted(j + 1); out.add(LlTok('c', line.substring(j + 2, e - 1))); j = e }
                c == '"' -> { val e = quoted(j); out.add(LlTok('s', line.substring(j + 1, e - 1))); j = e }
                c == '.' && line.startsWith("...", j) -> { out.add(LlTok('.', "...")); j += 3 }
                c.isDigit() || ((c == '-' || c == '+') && j + 1 < n && line[j + 1].isDigit()) -> {
                    var e = j + 1
                    if (c == '0' && e < n && line[e] == 'x') { e++; while (e < n && line[e].isLetterOrDigit()) e++ }
                    else while (e < n && (line[e].isDigit() || line[e] in ".eE" || ((line[e] == '-' || line[e] == '+') && line[e - 1] in "eE"))) e++
                    if (e < n && line[e] == ':' ) { out.add(LlTok('n', line.substring(j, e))); j = e } // block label "12:"
                    else { out.add(LlTok('n', line.substring(j, e))); j = e }
                }
                c == 'f' && line.startsWith("f0x", j) -> { // LLVM 23 bit-pattern float literal
                    var e = j + 3; while (e < n && line[e].isLetterOrDigit()) e++
                    out.add(LlTok('n', line.substring(j, e))); j = e
                }
                c.isLetter() || c == '_' || c == '$' || c == '.' -> {
                    val e = ident(j)
                    out.add(LlTok('w', line.substring(j, e))); j = e
                }
                else -> { out.add(LlTok('p', c.toString())); j++ }
            }
        }
        // drop trailing metadata attachments: ", !dbg !12"
        var end = out.size
        while (end >= 3 && out[end - 1].k == '!' && out[end - 2].k == '!' && out[end - 3].s == ",") end -= 3
        return if (end == out.size) out else out.subList(0, end)
    }

    private fun unq(s: String) = if (s.startsWith("\"")) s.substring(1, s.length - 1) else s

    private fun peek(o: Int = 0): LlTok? = toks.getOrNull(p + o)
    private fun next(): LlTok = toks.getOrNull(p++) ?: err("unexpected end of line")
    private fun isP(s: String, o: Int = 0) = peek(o)?.let { it.k == 'p' && it.s == s } == true
    private fun isW(s: String, o: Int = 0) = peek(o)?.let { it.k == 'w' && it.s == s } == true
    private fun expectP(s: String) { val t = next(); if (t.k != 'p' || t.s != s) err("expected '$s', got '${t.s}'") }
    private fun expectW(s: String) { val t = next(); if (t.k != 'w' || t.s != s) err("expected '$s', got '${t.s}'") }
    private fun acceptP(s: String): Boolean { if (isP(s)) { p++; return true }; return false }
    private fun acceptW(s: String): Boolean { if (isW(s)) { p++; return true }; return false }

    private fun skipGroup() { // after a word: "(...)" balanced
        if (!isP("(")) return
        var d = 0
        do { val t = next(); if (t.k == 'p' && t.s == "(") d++; if (t.k == 'p' && t.s == ")") d-- } while (d > 0)
    }

    // ---- types ---------------------------------------------------------------------------------------
    private fun isTypeStart(): Boolean {
        val t = peek() ?: return false
        if (t.k == '%') return true
        if (t.k == 'p') return t.s == "{" || t.s == "[" || t.s == "<"
        if (t.k != 'w') return false
        return t.s == "void" || t.s == "ptr" || t.s == "float" || t.s == "double" || t.s == "half" || t.s == "label" ||
            t.s == "token" || t.s == "metadata" || (t.s.length > 1 && t.s[0] == 'i' && t.s.substring(1).all { it.isDigit() })
    }

    private fun type(): LTy {
        var t = baseType()
        while (isP("(")) { // function type
            p++
            val ps = ArrayList<LTy>(); var va = false
            while (!isP(")")) { if (peek()?.k == '.') { p++; va = true } else { ps.add(type()); while (!isP(",") && !isP(")")) attr() }; acceptP(",") }
            expectP(")")
            t = LFn(t, ps, va)
        }
        return t
    }

    private fun baseType(): LTy {
        val t = next()
        when (t.k) {
            '%' -> return m.types[t.s] ?: err("unknown type %${t.s}")
            'p' -> when (t.s) {
                "[" -> { val n = next().s.toLong(); expectW("x"); val e = type(); expectP("]"); return LArr(n, e) }
                "{" -> { val fs = ArrayList<LTy>(); while (!isP("}")) { fs.add(type()); acceptP(",") }; expectP("}"); return LStruct(fs, false) }
                "<" -> {
                    if (acceptP("{")) { val fs = ArrayList<LTy>(); while (!isP("}")) { fs.add(type()); acceptP(",") }; expectP("}"); expectP(">"); return LStruct(fs, true) }
                    val n = next().s.toLong(); expectW("x"); val e = type(); expectP(">"); return LVec(n, e)
                }
            }
            'w' -> {
                when (t.s) {
                    "void" -> return LVoid
                    "ptr" -> { if (isW("addrspace")) { p++; skipGroup() }; return LPtr }
                    "float" -> return LFloat
                    "double" -> return LDouble
                    "label" -> return LLabel
                    "token", "metadata" -> return LOther
                }
                if (t.s[0] == 'i') t.s.substring(1).toIntOrNull()?.let { return LInt(it) }
            }
        }
        err("bad type '${t.s}'")
    }

    private fun namedType() {
        val name = next().s; expectP("="); expectW("type")
        val st = m.types[name]!!
        if (isW("opaque")) return
        val packed = isP("<")
        val t = type() as? LStruct ?: err("named type is not a struct")
        st.fields = t.fields
        if (packed != t.packed) err("packed mismatch")
    }

    // ---- attributes ----------------------------------------------------------------------------------
    private val withInt = setOf("align", "alignstack", "addrspace")
    private fun attr() {
        val t = next()
        if (t.k == '#' || t.k == '!') return
        if (t.k == 'w') {
            if (t.s in withInt && peek()?.k == 'n') { if (t.s == "align") lastAlign = peek()!!.s.toInt(); p++; return }
            skipGroup(); return
        }
        if (t.k == 's') { if (isP("=")) { p++; next() }; return } // "key"="value" string attrs
        err("unexpected attribute token '${t.s}'")
    }

    private fun isValueStart(): Boolean {
        val t = peek() ?: return false
        return when (t.k) {
            '%', '@', 'n', 'c' -> true
            'p' -> t.s == "[" || t.s == "{" || t.s == "<" || ((t.s == "+" || t.s == "-") && toks.getOrNull(p + 1)?.k == 'w') // +inf, -qnan
            'w' -> t.s in valueWords
            else -> false
        }
    }
    private val valueWords = setOf("true", "false", "null", "undef", "poison", "zeroinitializer", "none", "getelementptr",
        "ptrtoint", "inttoptr", "bitcast", "addrspacecast", "trunc", "zext", "sext", "add", "sub", "mul", "shl", "xor", "and", "or",
        "dso_local_equivalent", "no_cfi", "splat", "blockaddress")

    // ---- values ---------------------------------------------------------------------------------------
    private fun value(ty: LTy): LV {
        val t = next()
        when (t.k) {
            '%' -> return VLocal(t.s)
            '@' -> return VGlobal(t.s)
            'n' -> {
                val s = t.s
                if (s.startsWith("f0x")) {
                    val bits = s.substring(3).toULong(16).toLong()
                    return when (ty) { LFloat -> VFloat(Float.fromBits(bits.toInt()).toDouble()); LDouble -> VFloat(Double.fromBits(bits)); else -> err("float literal $s for $ty") }
                }
                if (ty == LFloat || ty == LDouble) {
                    if (s.startsWith("0x")) {
                        if (s.length != 18 || !s[2].isDigit() && s[2] !in "ABCDEFabcdef") err("unsupported float literal $s")
                        return VFloat(Double.fromBits(s.substring(2).toULong(16).toLong()))
                    }
                    return VFloat(s.toDouble())
                }
                if (s.startsWith("0x")) err("unsupported hex literal $s")
                if ('.' in s || 'e' in s) return VFloat(s.toDouble())
                val bits = (ty as? LInt)?.bits ?: 64
                if (bits > 64) { val v = s.toLongOrNull() ?: err("integer literal too wide: $s"); return VInt(v) }
                return VInt(s.toLongOrNull() ?: s.toULong().toLong())
            }
            'c' -> return VStr(cstr(t.s))
            'p' -> when (t.s) {
                // LLVM 23 special floats: +inf, -inf, +qnan, -snan, +nan(0x...) (NaN payloads are not kept)
                "+", "-" -> if (peek()?.k == 'w') {
                    val w = next().s
                    if (isP("(")) skipGroup()
                    return VFloat(when (w) { "inf" -> if (t.s == "-") Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY; "qnan", "snan", "nan" -> Double.NaN; else -> err("float literal ${t.s}$w") })
                }
                "[" -> { val es = ArrayList<Pair<LTy, LV>>(); while (!isP("]")) { val et = type(); es.add(et to value(et)); acceptP(",") }; expectP("]"); return VAgg(es) }
                "{" -> { val es = ArrayList<Pair<LTy, LV>>(); while (!isP("}")) { val et = type(); es.add(et to value(et)); acceptP(",") }; expectP("}"); return VAgg(es) }
                "<" -> {
                    if (acceptP("{")) { val es = ArrayList<Pair<LTy, LV>>(); while (!isP("}")) { val et = type(); es.add(et to value(et)); acceptP(",") }; expectP("}"); expectP(">"); return VAgg(es) }
                    err("vector constants are not supported")
                }
            }
            'w' -> when (t.s) {
                "true" -> return VInt(1)
                "false" -> return VInt(0)
                "null", "none" -> return VNull
                "undef", "poison" -> return VUndef
                "zeroinitializer" -> return VZero
                "dso_local_equivalent", "no_cfi" -> return value(ty)
                "getelementptr" -> {
                    while (peek()?.k == 'w') { val w = next().s; if (w == "inrange") skipGroup() }
                    expectP("(")
                    val src = type(); expectP(",")
                    val ops = ArrayList<Pair<LTy, LV>>()
                    while (!isP(")")) { val ot = type(); ops.add(ot to value(ot)); acceptP(",") }
                    expectP(")")
                    return VExpr("gep", LPtr, ops, src)
                }
                "ptrtoint", "inttoptr", "bitcast", "addrspacecast", "trunc", "zext", "sext" -> {
                    expectP("("); val ot = type(); val v = value(ot); expectW("to"); val to = type(); expectP(")")
                    return VExpr(t.s, to, listOf(ot to v))
                }
                "add", "sub", "mul", "shl", "xor", "and", "or" -> {
                    while (isW("nuw") || isW("nsw")) p++
                    expectP("("); val at = type(); val a = value(at); expectP(","); val bt = type(); val b = value(bt); expectP(")")
                    return VExpr(t.s, at, listOf(at to a, bt to b))
                }
            }
        }
        err("bad value '${t.s}'")
    }

    private fun cstr(s: String): ByteArray {
        val out = ArrayList<Byte>(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 2 < s.length + 1 && i + 1 < s.length) {
                if (s[i + 1] == '\\') { out.add('\\'.code.toByte()); i += 2; continue }
                out.add(s.substring(i + 1, i + 3).toInt(16).toByte()); i += 3; continue
            }
            // printable ASCII appear literally; non-ASCII never do (LLVM escapes them)
            out.add(c.code.toByte()); i++
        }
        return out.toByteArray()
    }

    /** Type, then parameter/return attributes, then a value: one call argument or typed operand. */
    private var lastAlign = 0
    private fun typedOperand(): Pair<LTy, LV> {
        val ty = type()
        lastAlign = 0
        while (!isValueStart()) attr()
        return ty to value(ty)
    }

    // ---- globals ---------------------------------------------------------------------------------------
    private val linkageWords = setOf("private", "internal", "external", "weak", "linkonce", "linkonce_odr", "weak_odr", "common",
        "appending", "extern_weak", "available_externally", "dso_local", "dso_preemptable", "hidden", "protected", "default",
        "unnamed_addr", "local_unnamed_addr", "externally_initialized", "thread_local", "dllimport", "dllexport")

    private fun global() {
        val name = next().s; expectP("=")
        var external = false
        while (true) {
            val t = peek() ?: err("bad global")
            if (t.k == 'w' && t.s in linkageWords) { p++; if (t.s == "external" || t.s == "extern_weak") external = true; if (t.s == "thread_local") skipGroup(); continue }
            if (t.k == 'w' && t.s == "addrspace") { p++; skipGroup(); continue }
            break
        }
        val kind = next().s
        if (kind == "alias") { val at = type(); expectP(","); val vt = type(); m.aliases[name] = value(vt); return }
        if (kind == "ifunc") err("ifunc is not supported")
        if (kind != "global" && kind != "constant") err("bad global kind $kind")
        val ty = type()
        val init = if (!external && isValueStart()) value(ty) else null
        var align = 0
        while (acceptP(",")) {
            if (acceptW("align")) align = next().s.toInt()
            else if (acceptW("section") || acceptW("partition")) next()
            else if (isW("comdat")) { p++; skipGroup() }
            else break
        }
        m.globals[name] = LlGlobal(name, ty, init, align, kind == "constant")
    }

    private val fnPrefixWords = setOf("private", "internal", "external", "weak", "linkonce", "linkonce_odr", "weak_odr", "common",
        "extern_weak", "available_externally", "dso_local", "dso_preemptable", "hidden", "protected", "default", "fastcc", "coldcc",
        "ccc", "tailcc", "swiftcc", "preserve_mostcc", "preserve_allcc", "noundef", "nonnull", "noalias", "zeroext", "signext",
        "inreg", "dllimport", "dllexport")

    private fun func(isDef: Boolean): LlFunc {
        p++ // define / declare
        var ret: LTy? = null
        while (true) {
            if (isTypeStart()) { val save = p; val t = type(); if (peek()?.k == '@') { ret = t; break }; p = save }
            val t = next()
            if (t.k == 'w' && t.s in withInt && peek()?.k == 'n') { p++; continue }
            if (t.k == 'w') { skipGroup(); continue }
            err("bad function header")
        }
        val name = next().s
        expectP("(")
        val params = ArrayList<LlParam>(); var va = false
        var k = 0
        while (!isP(")")) {
            if (peek()?.k == '.') { p++; va = true; acceptP(","); continue }
            val t = type()
            while (!isP(",") && !isP(")") && peek()?.k != '%') attr()
            val pn = if (peek()?.k == '%') next().s else "\$arg${k}"
            params.add(LlParam(t, pn)); k++
            acceptP(",")
        }
        expectP(")")
        val f = LlFunc(name, ret!!, params, va)
        if (m.funcs[name]?.isDecl != false || isDef) m.funcs[name] = f
        return f
    }

    // ---- instructions ------------------------------------------------------------------------------------
    private val fmf = setOf("nnan", "ninf", "nsz", "arcp", "contract", "afn", "reassoc", "fast")
    private val binops = setOf("add", "sub", "mul", "udiv", "sdiv", "urem", "srem", "shl", "lshr", "ashr", "and", "or", "xor",
        "fadd", "fsub", "fmul", "fdiv", "frem")
    private val casts = setOf("trunc", "zext", "sext", "fptrunc", "fpext", "fptoui", "fptosi", "uitofp", "sitofp", "ptrtoint",
        "inttoptr", "bitcast", "addrspacecast")

    private fun skipFlags() { while (peek()?.let { it.k == 'w' && (it.s in fmf || it.s in setOf("nuw", "nsw", "exact", "disjoint", "nneg", "samesign", "inbounds", "nusw")) } == true) p++ }

    private fun inst(): LlInst {
        var res: String? = null
        if (peek()?.k == '%' && isP("=", 1)) { res = next().s; p++ }
        var op = next().s
        if (op == "tail" || op == "musttail" || op == "notail") op = next().s
        when (op) {
            in binops -> {
                skipFlags(); val t = type(); val a = value(t); expectP(","); val b = value(t)
                return LlInst(res, op, t, listOf(t to a, t to b))
            }
            "fneg" -> { skipFlags(); val t = type(); return LlInst(res, op, t, listOf(t to value(t))) }
            "icmp", "fcmp" -> {
                skipFlags(); val pr = next().s; val t = type(); val a = value(t); expectP(","); val b = value(t)
                return LlInst(res, op, LInt(1), listOf(t to a, t to b)).also { it.pred = pr }
            }
            in casts -> {
                skipFlags(); val t = type(); val a = value(t); expectW("to"); val to = type()
                return LlInst(res, op, to, listOf(t to a))
            }
            "freeze" -> { val t = type(); return LlInst(res, op, t, listOf(t to value(t))) }
            "select" -> {
                skipFlags(); val c = typedOperand(); expectP(","); val a = typedOperand(); expectP(","); val b = typedOperand()
                return LlInst(res, op, a.first, listOf(c, a, b))
            }
            "phi" -> {
                skipFlags(); val t = type()
                val inc = ArrayList<Pair<LV, String>>()
                while (acceptP("[")) { val v = value(t); expectP(","); val l = next().s; expectP("]"); inc.add(v to l); acceptP(",") }
                return LlInst(res, op, t).also { it.incoming = inc }
            }
            "alloca" -> {
                acceptW("inalloca"); val t = type(); var cnt: Pair<LTy, LV> = LInt(32) to VInt(1); var al = 0
                while (acceptP(",")) { if (acceptW("align")) al = next().s.toInt() else if (acceptW("addrspace")) skipGroup() else cnt = typedOperand() }
                return LlInst(res, op, LPtr, listOf(cnt)).also { it.srcTy = t; it.align = al }
            }
            "load" -> {
                acceptW("atomic"); acceptW("volatile"); val t = type(); expectP(","); val ptr = typedOperand()
                var al = 0
                while (peek() != null) { if (acceptW("align")) al = next().s.toInt() else if (isW("syncscope")) { p++; skipGroup() } else p++ }
                return LlInst(res, op, t, listOf(ptr)).also { it.srcTy = t; it.align = al }
            }
            "store" -> {
                acceptW("atomic"); acceptW("volatile"); val v = typedOperand(); expectP(","); val ptr = typedOperand()
                var al = 0
                while (peek() != null) { if (acceptW("align")) al = next().s.toInt() else if (isW("syncscope")) { p++; skipGroup() } else p++ }
                return LlInst(null, op, LVoid, listOf(v, ptr)).also { it.align = al }
            }
            "getelementptr" -> {
                skipFlags(); if (isW("inrange")) { p++; skipGroup() }
                val src = type(); val ops = ArrayList<Pair<LTy, LV>>()
                while (acceptP(",")) { if (isW("inrange")) { p++; skipGroup() }; ops.add(typedOperand()) }
                return LlInst(res, op, LPtr, ops).also { it.srcTy = src }
            }
            "extractvalue" -> {
                val a = typedOperand(); val ix = ArrayList<Int>(); while (acceptP(",")) ix.add(next().s.toInt())
                return LlInst(res, op, LVoid, listOf(a)).also { it.idx = ix }
            }
            "insertvalue" -> {
                val a = typedOperand(); expectP(","); val v = typedOperand(); val ix = ArrayList<Int>(); while (acceptP(",")) ix.add(next().s.toInt())
                return LlInst(res, op, a.first, listOf(a, v)).also { it.idx = ix }
            }
            "call" -> return call(res)
            "br" -> {
                if (acceptW("label")) return LlInst(null, op, LVoid).also { it.labels = listOf(next().s) }
                val c = typedOperand(); expectP(","); expectW("label"); val a = next().s; expectP(","); expectW("label"); val b = next().s
                return LlInst(null, op, LVoid, listOf(c)).also { it.labels = listOf(a, b) }
            }
            "switch" -> {
                val v = typedOperand(); expectP(","); expectW("label"); val d = next().s; expectP("[")
                val ls = arrayListOf(d); val cs = ArrayList<Long>()
                while (!isP("]")) { val t = type(); cs.add((value(t) as VInt).v); expectP(","); expectW("label"); ls.add(next().s) }
                return LlInst(null, op, LVoid, listOf(v)).also { it.labels = ls; it.cases = cs }
            }
            "ret" -> { if (acceptW("void")) return LlInst(null, op, LVoid); val v = typedOperand(); return LlInst(null, op, LVoid, listOf(v)) }
            "unreachable" -> return LlInst(null, op, LVoid)
            "fence" -> return LlInst(null, op, LVoid)
            "atomicrmw" -> {
                acceptW("volatile"); val o = next().s; val ptr = typedOperand(); expectP(","); val v = typedOperand()
                return LlInst(res, op, v.first, listOf(ptr, v)).also { it.flag = o }
            }
            "cmpxchg" -> {
                acceptW("weak"); acceptW("volatile"); val ptr = typedOperand(); expectP(","); val c = typedOperand(); expectP(","); val n = typedOperand()
                return LlInst(res, op, LStruct(listOf(c.first, LInt(1)), false), listOf(ptr, c, n))
            }
        }
        err("unsupported instruction '$op'")
    }

    private val callPre = setOf("fastcc", "coldcc", "ccc", "tailcc", "swiftcc", "noundef", "nonnull", "noalias", "zeroext", "signext",
        "inreg", "dereferenceable", "dereferenceable_or_null", "range", "nofpclass", "align")

    private fun call(res: String?): LlInst {
        skipFlags()
        while (peek()?.let { it.k == 'w' && it.s in callPre } == true) { val w = next().s; if (w == "align") p++ else skipGroup() }
        val t = type()
        val fnTy: LFn; val callee: LV
        if (t is LFn) { fnTy = t; callee = value(LPtr) } else { callee = value(LPtr); fnTy = LFn(t, emptyList(), false) }
        expectP("(")
        val args = ArrayList<Pair<LTy, LV>>(); val aligns = ArrayList<Int>()
        while (!isP(")")) { args.add(typedOperand()); aligns.add(lastAlign); acceptP(",") }
        expectP(")")
        return LlInst(res, "call", fnTy.ret, args).also { it.callee = callee; it.fnTy = fnTy; it.argAlign = aligns }
    }
}
