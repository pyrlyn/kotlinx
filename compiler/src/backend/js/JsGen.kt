package newkotlin

/*
 * LLVM IR (LlModule) -> JavaScript.
 *
 * Memory model: one ArrayBuffer with typed-array views (I8 U8 I16 U16 I32 F32 F64). The IR has wasm32's data
 * layout (driver/JsDriver.kt): a pointer, size_t or long is 4 bytes in memory and one int32 value, an address
 * below 2 GB.
 *
 * Values: every SSA value is a list of JS scalars ("parts"): i1..i32 and ptr one int32 (i8/i16 kept sign-extended,
 * i1 as a boolean or 0/1), i64 two int32 parts (low, high), float/double one number, structs the concatenation of
 * their fields. A function returns its first part; further parts go through the globals H, H1, H2, ...
 *
 * Control flow: structured with Ramsey's algorithm ("Beyond Relooper", ICFP 2022) over the dominator tree:
 * loop headers become `L: for(;;){}`, merge nodes `B: { ... }` blocks left with `break B`, back edges
 * `continue L`. Phi nodes become assignments on the edges. Irreducible functions fall back to a dispatch loop.
 *
 * Compact output (the default): defined functions get short names (`$a`, `$b`, ..., skipping the runtimeNames
 * that runtime.js uses) and function bodies have no line breaks. `compact = false` keeps LLVM names and one
 * statement per line, for reading and profiling.
 */

private fun isI64(t: LTy) = t is LInt && t.bits == 64
/** Odd-width integers (i33..i128 except i64, e.g. SCEV's i65 closed forms): one BigInt part, kept unsigned. */
private fun isWide(t: LTy) = t is LInt && t.bits > 32 && t.bits != 64 && t.bits <= 128

class JsGen(private val m: LlModule, private val warn: (String) -> Unit = {}, private val runtimeNames: Set<String> = emptySet(),
            private val compact: Boolean = true) {
    // ---- layout (wasm32, "e-m:e-p:32:32-i64:64-i128:128-n32-S128") ---------------------------------------
    fun sizeOf(t: LTy): Long = when (t) {
        is LInt -> when { t.bits <= 8 -> 1; t.bits <= 16 -> 2; t.bits <= 32 -> 4; t.bits <= 64 -> 8; else -> 16 }
        LPtr -> 4; LFloat -> 4; LDouble -> 8
        is LArr -> t.n * sizeOf(t.elem)
        is LStruct -> { var o = 0L; for (f in t.fields) { if (!t.packed) o = alignUp(o, alignOf(f)); o += sizeOf(f) }; if (t.packed) o else alignUp(o, alignOf(t)) }
        else -> throw CompileError("JS backend: no size for $t")
    }
    fun alignOf(t: LTy): Long = when (t) {
        is LInt -> minOf(sizeOf(t), 16)
        LDouble -> 8; LPtr, LFloat -> 4
        is LArr -> alignOf(t.elem)
        is LStruct -> if (t.packed) 1 else t.fields.maxOfOrNull { alignOf(it) } ?: 1
        else -> 1
    }
    private fun alignUp(x: Long, a: Long) = (x + a - 1) / a * a
    fun fieldOffset(t: LStruct, k: Int): Long {
        var o = 0L
        for (i in 0..k) { if (!t.packed) o = alignUp(o, alignOf(t.fields[i])); if (i < k) o += sizeOf(t.fields[i]) }
        return o
    }

    /** Part kinds: 'i' int32, 'd' double, 'f' float, 'b' BigInt (odd-width integers). */
    fun parts(t: LTy): List<Char> = when (t) {
        is LInt -> if (t.bits <= 32) listOf('i') else if (t.bits == 64) listOf('i', 'i') else if (isWide(t)) listOf('b') else throw CompileError("JS backend: i${t.bits} values are not supported")
        LPtr -> listOf('i'); LFloat -> listOf('f'); LDouble -> listOf('d')
        is LStruct -> t.fields.flatMap { parts(it) }
        is LArr -> (0 until t.n).flatMap { parts(t.elem) }
        LVoid -> emptyList()
        else -> throw CompileError("JS backend: unsupported value type $t")
    }

    // ---- names --------------------------------------------------------------------------------------------
    private val jsNames = HashMap<String, String>()
    private val usedJsNames = HashSet<String>()
    private var shortCount = 0
    fun jsName(llName: String): String = jsNames.getOrPut(llName) {
        if (compact && m.funcs[llName]?.isDecl == false) {
            while (true) {
                var k = shortCount++; val s = StringBuilder("$")
                do { s.append("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"[k % 52]); k = k / 52 - 1 } while (k >= 0)
                val n = s.toString()
                if (n !in runtimeNames && usedJsNames.add(n)) return@getOrPut n
            }
        }
        val base = "$" + (if (llName.startsWith("\u0001_")) llName.substring(2) else llName.removePrefix("\\01_"))
            .map { if (it.isLetterOrDigit() || it == '_') it else '$' }.joinToString("")
        var n = base; var k = 1
        while (!usedJsNames.add(n)) n = base + "$" + (k++)
        n
    }

    // ---- globals and data ------------------------------------------------------------------------------------
    val dataStart = 16L
    private val addr = HashMap<String, Long>()
    private val fnIndex = LinkedHashMap<String, Int>()   // address-taken functions -> table index
    private var data = ByteArray(1024)
    var dataEnd = dataStart; private set
    val ctors = ArrayList<String>()

    private fun fnIdx(name: String): Int = fnIndex.getOrPut(name) { fnIndex.size + 1 }

    private fun resolveAlias(name: String): String {
        var n = name
        repeat(8) { val a = m.aliases[n] ?: return n; n = (a as? VGlobal)?.name ?: (a as? VExpr)?.ops?.firstOrNull()?.second?.let { (it as? VGlobal)?.name } ?: throw CompileError("JS backend: alias $n") }
        return n
    }

    fun constInt(v: LV): Long = when (v) {
        is VInt -> v.v
        VNull, VUndef, VZero -> 0
        is VGlobal -> {
            val n = resolveAlias(v.name)
            addr[n] ?: if (m.funcs.containsKey(n)) fnIdx(n).toLong() else throw CompileError("JS backend: undefined symbol @$n")
        }
        is VExpr -> when (v.op) {
            "gep" -> constInt(v.ops[0].second) + gepConstOffset(v.srcTy!!, v.ops.drop(1).map { constInt(it.second) })
            "ptrtoint", "inttoptr", "bitcast", "addrspacecast", "zext" -> constInt(v.ops[0].second)
            "sext" -> constInt(v.ops[0].second)
            "trunc" -> { val x = constInt(v.ops[0].second); val b = (v.ty as LInt).bits; if (b >= 64) x else (x shl (64 - b)) shr (64 - b) }
            "add" -> constInt(v.ops[0].second) + constInt(v.ops[1].second)
            "sub" -> constInt(v.ops[0].second) - constInt(v.ops[1].second)
            "mul" -> constInt(v.ops[0].second) * constInt(v.ops[1].second)
            "and" -> constInt(v.ops[0].second) and constInt(v.ops[1].second)
            "or" -> constInt(v.ops[0].second) or constInt(v.ops[1].second)
            "xor" -> constInt(v.ops[0].second) xor constInt(v.ops[1].second)
            "shl" -> constInt(v.ops[0].second) shl constInt(v.ops[1].second).toInt()
            else -> throw CompileError("JS backend: constant expression ${v.op}")
        }
        else -> throw CompileError("JS backend: not an integer constant: $v")
    }

    private fun gepConstOffset(src: LTy, idx: List<Long>): Long {
        var off = idx[0] * sizeOf(src); var t = src
        for (k in 1 until idx.size) {
            when (val tt = t) {
                is LStruct -> { off += fieldOffset(tt, idx[k].toInt()); t = tt.fields[idx[k].toInt()] }
                is LArr -> { off += idx[k] * sizeOf(tt.elem); t = tt.elem }
                else -> throw CompileError("JS backend: bad gep into $t")
            }
        }
        return off
    }

    private fun ensure(n: Long) { if (n > data.size) data = data.copyOf(maxOf(n.toInt(), data.size * 2)) }
    private fun put(off: Long, v: Long, size: Int) { ensure(off + size); for (k in 0 until size) data[(off + k).toInt()] = (v ushr (8 * k)).toByte() }

    private fun writeConst(off: Long, t: LTy, v: LV) {
        when {
            v === VZero || v === VUndef -> {}
            t is LStruct -> { val a = v as VAgg; for (k in a.elems.indices) writeConst(off + fieldOffset(t, k), t.fields[k], a.elems[k].second) }
            t is LArr -> when (v) {
                is VStr -> { ensure(off + v.bytes.size); v.bytes.copyInto(data, off.toInt()) }
                is VAgg -> { val es = sizeOf(t.elem); for (k in v.elems.indices) writeConst(off + k * es, t.elem, v.elems[k].second) }
                else -> throw CompileError("JS backend: array initializer $v")
            }
            t == LDouble -> put(off, (v as VFloat).v.toRawBits(), 8)
            t == LFloat -> put(off, (v as VFloat).v.toFloat().toRawBits().toLong(), 4)
            t == LPtr -> put(off, constInt(v), 4)
            t is LInt -> put(off, constInt(v), sizeOf(t).toInt())
            else -> throw CompileError("JS backend: initializer of type $t")
        }
    }

    private fun isZeroInit(v: LV?): Boolean = when (v) {
        null, VNull, VUndef, VZero -> true
        is VInt -> v.v == 0L
        is VFloat -> v.v.toRawBits() == 0L
        is VStr -> v.bytes.all { it.toInt() == 0 }
        is VAgg -> v.elems.all { isZeroInit(it.second) }
        else -> false
    }

    /** Zero-initialized globals go last (like .bss), so dataBytes() trims them from the data segment. */
    private fun layoutGlobals() {
        var a = dataStart
        for (g in m.globals.values.sortedBy { isZeroInit(it.init) }) {
            if (g.name.startsWith("llvm.")) continue
            if (g.init == null) { warn("external global @${g.name} (zero-filled)") }
            val al = maxOf(g.align.toLong(), alignOf(g.ty), 1)
            a = alignUp(a, al)
            addr[g.name] = a
            a += maxOf(sizeOf(g.ty), 1)
        }
        dataEnd = alignUp(a, 16)
        for (g in m.globals.values) {
            if (g.name == "llvm.global_ctors" && g.init is VAgg) {
                (g.init.elems.map { it.second as VAgg }.sortedBy { (it.elems[0].second as VInt).v })
                    .forEach { ctors.add(jsName((it.elems[1].second as VGlobal).name)) }
            }
            if (g.name.startsWith("llvm.")) continue
            g.init?.let { writeConst(addr[g.name]!!, g.ty, it) }
        }
    }

    fun dataBytes(): ByteArray = data.copyOfRange(dataStart.toInt(), maxOf(dataStart, minOf(dataEnd, data.size.toLong())).toInt()).let { b ->
        var n = b.size; while (n > 0 && b[n - 1].toInt() == 0) n--; b.copyOf(n)
    }

    // ---- module ----------------------------------------------------------------------------------------------
    val externs = LinkedHashSet<String>()

    fun generate(): String {
        layoutGlobals()
        val out = StringBuilder()
        for (f in m.funcs.values) if (!f.isDecl) out.append(FnGen(f).gen())
        // Function table (index 0 = null). Externals whose address is taken are runtime functions.
        out.append("var T=[null")
        for (n in fnIndex.keys) { out.append(','); out.append(if (m.funcs[n]?.isDecl == false) jsName(n) else externName(n)) }
        out.append("];\n")
        return out.toString()
    }

    private fun externName(n: String): String { val j = jsName(n); externs.add(j); return j }

    // ---- per function -------------------------------------------------------------------------------------------
    private val reserved = setOf("do", "if", "in", "for", "let", "new", "try", "var", "case", "else", "enum", "eval", "null", "this",
        "true", "void", "with", "await", "break", "catch", "class", "const", "false", "super", "throw", "while", "yield", "delete",
        "export", "import", "public", "return", "static", "switch", "typeof", "default", "extends", "finally", "package", "private",
        "continue", "debugger", "function", "arguments", "interface", "protected", "implements", "instanceof", "of", "as", "get", "set")

    private inner class FnGen(val f: LlFunc) {
        val sb = StringBuilder()
        val declared = ArrayList<String>()
        var counter = 0
        fun newVar(): String {
            while (true) {
                var k = counter++; val s = StringBuilder()
                do { s.append('a' + k % 26); k = k / 26 - 1 } while (k >= 0)
                val n = s.toString()
                if (n !in reserved) { declared.add(n); return n }
            }
        }

        val blocks = f.blocks
        val bIndex = HashMap<String, Int>().also { m -> blocks.forEachIndexed { i, b -> m[b.name] = i } }
        val succ = blocks.map { b -> b.insts.last().labels.map { bIndex[it] ?: throw CompileError("JS backend: unknown label %$it in @${f.name}") } }
        val defs = HashMap<String, LlInst>()
        val vals = HashMap<String, List<String>>()   // SSA local -> part expressions (var names or inlined code)
        val inlineSet = HashSet<String>()
        val lazyHi = HashSet<String>()   // i64 loads whose high word is re-read at each use (see analyze)
        val paramTypes = HashMap<String, LTy>()
        var fp: String? = null
        var depthProbe = false
        var frame = 0L
        val allocaOff = HashMap<String, Long>()

        fun typeOfLocal(n: String): LTy = defs[n]?.let { resultType(it) } ?: paramTypes[n] ?: throw CompileError("JS backend: unknown %$n in @${f.name}")

        fun resultType(i: LlInst): LTy = when (i.op) {
            "extractvalue" -> { var t = i.ops[0].first; for (k in i.idx) t = when (t) { is LStruct -> t.fields[k]; is LArr -> t.elem; else -> t }; t }
            else -> i.ty
        }

        // ---- expressions -----------------------------------------------------------------------------------------
        fun atom(e: String) = e.isNotEmpty() && (e[0].isLetter() || e[0] == '$' || e[0] == '_' || e[0].isDigit()) && e.all { it.isLetterOrDigit() || it == '$' || it == '_' || it == '.' }
        fun w(e: String) = if (atom(e)) e else "($e)"

        fun num(d: Double): String = when {
            d.isNaN() -> "NaN"; d == Double.POSITIVE_INFINITY -> "Infinity"; d == Double.NEGATIVE_INFINITY -> "-Infinity"
            d == 0.0 && 1.0 / d < 0 -> "-0"
            d == kotlin.math.floor(d) && kotlin.math.abs(d) < 1e15 -> d.toLong().toString()
            // Kotlin/Native's Double.toString does not always read back as d (e.g. 2^-25, 2^-1017 or some integers
            // above 1e17); then d is written exactly as m * 2^e (-1074 <= e <= 971, so 2**e is exact in JS).
            else -> d.toString().takeIf { it.toDouble() == d } ?: run {
                val b = d.toRawBits(); val be = ((b ushr 52) and 0x7ff).toInt()
                var m = if (be == 0) b and 0xfffffffffffffL else b and 0xfffffffffffffL or (1L shl 52)
                var e = if (be == 0) -1074 else be - 1075
                while (m and 1L == 0L) { m = m shr 1; e++ }
                "(" + (if (b < 0) "-" else "") + m + "*2**" + e + ")"
            }
        }

        /** JS expressions for each part of value v of type t. */
        fun ex(v: LV, t: LTy): List<String> = when (v) {
            is VLocal -> vals[v.name] ?: throw CompileError("JS backend: %${v.name} used before definition in @${f.name}")
            is VFloat -> listOf(num(if (t == LFloat) v.v.toFloat().toDouble() else v.v))
            VUndef, VZero, VNull -> parts(t).map { if (it == 'b') "0n" else "0" }
            is VAgg -> { val st = t; v.elems.flatMapIndexed { k, (et, ev) -> ex(ev, if (st is LStruct) st.fields[k] else et) } }
            is VGlobal -> {
                val n = resolveAlias(v.name)
                if (addr.containsKey(n)) listOf(addr[n].toString())
                else if (m.funcs.containsKey(n)) listOf(fnIdx(n).toString())
                else throw CompileError("JS backend: undefined symbol @$n")
            }
            else -> {
                val c = constInt(v)
                if (isI64(t)) listOf(c.toInt().toString(), (c shr 32).toInt().toString())
                else if (isWide(t)) listOf("BigInt.asUintN(${(t as LInt).bits},${c}n)")
                else if (t is LInt && t.bits == 1) listOf((c and 1).toString())
                else if (t is LInt && t.bits < 32) listOf(((c shl (64 - t.bits)) shr (64 - t.bits)).toInt().toString())
                else listOf(c.toInt().toString())
            }
        }
        fun ex1(v: LV, t: LTy) = ex(v, t)[0]
        fun op1(i: LlInst, k: Int) = ex1(i.ops[k].second, i.ops[k].first)

        // A "0=..." store targets a high word known to be zero (see hiZero): nothing to keep.
        fun emit(s: String) { if (s.startsWith("0=")) return; sb.append(s).append(";\n") }

        /** A simple (side-effect free, cheap) expression for e: binds it to a fresh variable if needed. */
        fun simple(e: String): String { if (atom(e) && !e.startsWith("-")) return e; val t = newVar(); emit("$t=$e"); return t }

        /** An i64 result whose high word is always 0: zext from 32 bits or less, `and` with such a constant, `lshr` by 32+. */
        fun hiZero(i: LlInst): Boolean {
            if (!isI64(resultType(i))) return false
            fun c(k: Int) = i.ops.getOrNull(k)?.second?.let { v -> if (v is VInt) v.v else null }
            return when (i.op) {
                "zext" -> (i.ops[0].first as? LInt)?.let { it.bits <= 32 } ?: false
                "and" -> listOf(c(0), c(1)).any { it != null && (it shr 32) == 0L }
                "lshr" -> c(1)?.let { (it and 63) >= 32 } ?: false
                else -> false
            }
        }

        /** Result variables (or inline expressions) for instruction i. */
        fun defineVars(i: LlInst): List<String> {
            val r = i.res!!
            vals[r]?.let { return it }
            val vs = parts(resultType(i)).mapIndexed { k, _ -> if (k == 1 && hiZero(i)) "0" else newVar() }
            vals[r] = vs; return vs
        }
        fun setResult(i: LlInst, es: List<String>) {
            val r = i.res ?: return
            if (r in inlineSet) { vals[r] = es; return }
            if (useCount[r] == null) return // unused and pure
            val vs = defineVars(i)
            for (k in vs.indices) emit("${vs[k]}=${es[k]}")
        }

        val useCount = HashMap<String, Int>()

        fun norm(e: String, bits: Int) = when (bits) { 32 -> "${w(e)}|0"; 16 -> "${w(e)}<<16>>16"; 8 -> "${w(e)}<<24>>24"; 1 -> "${w(e)}&1"; else -> throw CompileError("JS backend: i$bits arithmetic") }
        fun u(e: String, bits: Int) = when (bits) { 32 -> "${w(e)}>>>0"; 16 -> "${w(e)}&65535"; 8 -> "${w(e)}&255"; 1 -> "+${w(e)}"; else -> throw CompileError("JS backend: i$bits") }
        fun bitsOf(t: LTy) = when (t) { is LInt -> t.bits; LPtr -> 32; else -> throw CompileError("JS backend: not an integer type $t") }

        // ---- memory -------------------------------------------------------------------------------------------
        /** Top-level characters of e (outside brackets). */
        fun topLevel(e: String): String { val r = StringBuilder(); var d = 0; for (c in e) { if (c in "([{") d++; if (d == 0) r.append(c); if (c in ")]}") d-- }; return r.toString() }
        fun sumOnly(e: String) = topLevel(e).all { it.isLetterOrDigit() || it in "\$_.+-" }
        /**
         * Address p as a `+` operand: a trailing `|0` is dropped (the heap index's `>>n`, or the typed array itself,
         * wraps the exact sum of int32 terms the same way), anything else with operators below `+` is parenthesized.
         */
        fun addrTerm(p: String): String {
            if (p.endsWith("|0") && sumOnly(p.dropLast(2))) return p.dropLast(2)
            return if (sumOnly(p)) p else "($p)"
        }
        fun loadExpr(t: LTy, p0: String, off: Long, align: Int): List<String> {
            val p = addrTerm(p0)
            val a = if (off == 0L) p else "$p+$off"
            return when (t) {
                is LInt -> when {
                    t.bits == 1 -> listOf("U8[$a]")
                    t.bits <= 8 -> listOf("I8[$a]")
                    t.bits <= 16 -> listOf(if (align >= 2 || align == 0) "I16[$a>>1]" else "_ld(${w(a)},2)")
                    t.bits <= 32 -> listOf(if (align >= 4 || align == 0) "I32[$a>>2]" else "_ld(${w(a)},4)")
                    t.bits == 64 -> if (align >= 4 || align == 0) listOf("I32[$a>>2]", "I32[$p+${off + 4}>>2]") else listOf("_ld(${w(a)},4)", "_ld($p+${off + 4},4)")
                    else -> throw CompileError("JS backend: load i${t.bits}")
                }
                LPtr -> listOf(if (align >= 4 || align == 0) "I32[$a>>2]" else "_ld(${w(a)},4)")
                LFloat -> listOf(if (align >= 4 || align == 0) "F32[$a>>2]" else "_ldf(${w(a)})")
                LDouble -> listOf(if (align >= 8 || align == 0) "F64[$a>>3]" else "_ldd(${w(a)})")
                is LStruct -> t.fields.indices.flatMap { k -> loadExpr(t.fields[k], p, off + fieldOffset(t, k), minOf(align, alignOf(t.fields[k]).toInt())) }
                is LArr -> (0 until t.n).flatMap { k -> loadExpr(t.elem, p, off + k * sizeOf(t.elem), minOf(align, alignOf(t.elem).toInt())) }
                else -> throw CompileError("JS backend: load $t")
            }
        }

        fun store(t: LTy, p0: String, off: Long, align: Int, es: List<String>) {
            val p = addrTerm(p0)
            val a = if (off == 0L) p else "$p+$off"
            fun st(heap: String, sh: Int, e: String) = emit(if (sh == 0) "$heap[$a]=$e" else "$heap[$a>>$sh]=$e")
            when (t) {
                is LInt -> when {
                    t.bits <= 8 -> st("I8", 0, es[0])
                    t.bits <= 16 -> if (align >= 2 || align == 0) st("I16", 1, es[0]) else emit("_st(${w(a)},${es[0]},2)")
                    t.bits <= 32 -> if (align >= 4 || align == 0) st("I32", 2, es[0]) else emit("_st(${w(a)},${es[0]},4)")
                    t.bits == 64 -> if (align >= 4 || align == 0) { st("I32", 2, es[0]); emit("I32[$p+${off + 4}>>2]=${es[1]}") }
                        else { emit("_st(${w(a)},${es[0]},4)"); emit("_st($p+${off + 4},${es[1]},4)") }
                    else -> throw CompileError("JS backend: store i${t.bits}")
                }
                LPtr -> if (align >= 4 || align == 0) st("I32", 2, es[0]) else emit("_st(${w(a)},${es[0]},4)")
                LFloat -> if (align >= 4 || align == 0) st("F32", 2, es[0]) else emit("_stf(${w(a)},${es[0]})")
                LDouble -> if (align >= 8 || align == 0) st("F64", 3, es[0]) else emit("_std(${w(a)},${es[0]})")
                is LStruct -> { var k0 = 0; t.fields.forEachIndexed { k, ft -> val n = parts(ft).size; store(ft, p, off + fieldOffset(t, k), minOf(align, alignOf(ft).toInt()), es.subList(k0, k0 + n)); k0 += n } }
                is LArr -> { val n = parts(t.elem).size; for (k in 0 until t.n.toInt()) store(t.elem, p, off + k * sizeOf(t.elem), minOf(align, alignOf(t.elem).toInt()), es.subList(k * n, k * n + n)) }
                else -> throw CompileError("JS backend: store $t")
            }
        }

        // ---- analysis ------------------------------------------------------------------------------------------
        fun localsOf(i: LlInst): List<String> {
            val r = ArrayList<String>()
            fun add(v: LV) { when (v) { is VLocal -> r.add(v.name); is VAgg -> v.elems.forEach { add(it.second) }; is VExpr -> v.ops.forEach { add(it.second) }; else -> {} } }
            i.ops.forEach { add(it.second) }; i.callee?.let { add(it) }
            return r
        }

        fun sideEffect(i: LlInst) = i.op == "store" || i.op == "call" || i.op == "atomicrmw" || i.op == "cmpxchg" || i.op == "fence"
        val pureOps = setOf("add", "sub", "mul", "sdiv", "udiv", "srem", "urem", "shl", "lshr", "ashr", "and", "or", "xor", "fadd", "fsub",
            "fmul", "fdiv", "frem", "fneg", "icmp", "fcmp", "trunc", "zext", "sext", "fptrunc", "fpext", "fptoui", "fptosi", "uitofp",
            "sitofp", "ptrtoint", "inttoptr", "bitcast", "addrspacecast", "freeze", "getelementptr", "select", "alloca", "extractvalue")

        fun analyze() {
            for (p in f.params) paramTypes[p.name] = p.ty
            for (b in blocks) for (i in b.insts) i.res?.let { defs[it] = i }
            // use counts; phi uses and cross-block uses disable inlining
            val noInline = HashSet<String>()
            val userAt = HashMap<String, Pair<Int, Int>>()
            val users = HashMap<String, ArrayList<Pair<Int, Int>>>()
            for ((bi, b) in blocks.withIndex()) for ((ii, i) in b.insts.withIndex()) {
                if (i.op == "phi") { for ((v, _) in i.incoming) if (v is VLocal) { useCount[v.name] = (useCount[v.name] ?: 0) + 2; noInline.add(v.name) }; continue }
                for (n in localsOf(i)) { useCount[n] = (useCount[n] ?: 0) + 1; userAt[n] = bi to ii; users.getOrPut(n) { ArrayList() }.add(bi to ii) }
            }
            val evalAts = blocks.map { b -> IntArray(b.insts.size) { it } }
            for ((bi, b) in blocks.withIndex()) {
                val evalAt = evalAts[bi]
                for (ii in b.insts.indices.reversed()) {
                    val i = b.insts[ii]; val r = i.res ?: continue
                    if (r in noInline || useCount[r] != 1 || userAt[r]?.first != bi) continue
                    // Multi-part values inline only as an i64 load into an icmp (a Kotlin array length against an
                    // index): the comparison then reads the high word only when the low words do not decide it.
                    if (parts(resultType(i)).size != 1 && !(i.op == "load" && isI64(i.ty) && b.insts[userAt[r]!!.second].op == "icmp")) continue
                    val pure = i.op in pureOps && !(i.op == "alloca" && bi != 0)
                    if (!pure && i.op != "load") continue
                    if (i.op == "alloca" && i.ops[0].second !is VInt) continue
                    val at = evalAt[userAt[r]!!.second]
                    if (i.op == "load" && (ii + 1 until at).any { sideEffect(b.insts[it]) }) continue
                    inlineSet.add(r); evalAt[ii] = at
                }
            }
            // An i64 load (a Kotlin array length) used by icmps in its block and by calls at the start of a block
            // only it branches to (the rt_oob report) keeps only its low word in a variable: each use re-reads the
            // high word from memory, so a bounds check usually decided by the low words never loads it.
            val onlyPred = IntArray(blocks.size) { -1 }
            for ((bi, ss) in succ.withIndex()) for (s in ss) onlyPred[s] = if (onlyPred[s] == -1 || onlyPred[s] == bi) bi else -2
            for ((bi, b) in blocks.withIndex()) for ((ii, i) in b.insts.withIndex()) {
                val r = i.res ?: continue
                if (i.op != "load" || !isI64(i.ty) || r in inlineSet || r in noInline || useCount[r] == null || i.align in 1..3) continue
                val p = i.ops[0].second
                if (!(p is VGlobal || p is VLocal && p.name !in inlineSet)) continue
                fun clean(blk: Int, from: Int, to: Int) = (from until to).none { sideEffect(blocks[blk].insts[it]) }
                if (users[r]!!.all { (ub, ui) ->
                        if (ub == bi) ui > ii && b.insts[ui].op == "icmp" && clean(bi, ii + 1, evalAts[bi][ui])
                        else blocks[ub].insts[ui].op == "call" && onlyPred[ub] == bi && clean(bi, ii + 1, b.insts.size) && clean(ub, 0, evalAts[ub][ui])
                    }) lazyHi.add(r)
            }
            // static frame for entry-block allocas with constant size
            for (i in blocks[0].insts) if (i.op == "alloca" && i.ops[0].second is VInt) {
                val al = maxOf(i.align.toLong(), alignOf(i.srcTy), 1)
                frame = alignUp(frame, al); allocaOff[i.res!!] = frame
                frame += maxOf(sizeOf(i.srcTy) * (i.ops[0].second as VInt).v, 1)
            }
            frame = alignUp(frame, 16)
            val dynamic = blocks.any { b -> b.insts.any { (it.op == "alloca" && !allocaOff.containsKey(it.res)) || (it.op == "call" && (it.callee as? VGlobal)?.name?.startsWith("llvm.stack") == true) } }
            if (frame > 0 || dynamic) fp = newVar()
            // Stack depth probes (runtime.c's bounded release compares llvm.stacksave / frameaddress with rt_rel_limit):
            // JS frames do not move S, so such functions count their nesting in D and _fa() reports S-(D<<12).
            val callees = blocks.flatMap { b -> b.insts.mapNotNull { (it.callee as? VGlobal)?.name } }.toSet()
            depthProbe = callees.any { it.startsWith("llvm.frameaddress") } ||
                (callees.any { it.startsWith("llvm.stacksave") } && callees.none { it.startsWith("llvm.stackrestore") })
        }

        // ---- instructions --------------------------------------------------------------------------------------
        fun genInst(i: LlInst) {
            val t = i.ty
            when (i.op) {
                "add", "sub", "mul", "sdiv", "udiv", "srem", "urem", "shl", "lshr", "ashr", "and", "or", "xor" -> {
                    if (isI64(t)) return genI64(i)
                    if (isWide(t)) return genWide(i)
                    val b = bitsOf(t); val x = op1(i, 0); val y = op1(i, 1)
                    val e = when (i.op) {
                        "add" -> if (b == 1) "${w(x)}^${w(y)}" else norm("${w(x)}+${w(y)}", b)
                        "sub" -> if (b == 1) "${w(x)}^${w(y)}" else norm("${w(x)}-${w(y)}", b)
                        "mul" -> if (b == 1) "${w(x)}&${w(y)}" else if (b == 32) "Math.imul($x,$y)" else norm("Math.imul($x,$y)", b)
                        "sdiv" -> norm("${w(x)}/${w(y)}|0", b)
                        "udiv" -> norm("${w(u(x, b))}/${w(u(y, b))}|0", b)
                        "srem" -> "${w(x)}%${w(y)}|0"
                        "urem" -> norm("${w(u(x, b))}%${w(u(y, b))}|0", b)
                        "shl" -> if (b == 32) "${w(x)}<<${w(y)}" else norm("${w(x)}<<${w(y)}", b)
                        "lshr" -> if (b == 32) "${w(x)}>>>${w(y)}|0" else norm("${w(u(x, b))}>>>${w(y)}", b)
                        "ashr" -> "${w(x)}>>${w(y)}"
                        "and" -> "${w(x)}&${w(y)}"
                        "or" -> "${w(x)}|${w(y)}"
                        else -> "${w(x)}^${w(y)}"
                    }
                    setResult(i, listOf(e))
                }
                "fadd", "fsub", "fmul", "fdiv", "frem" -> {
                    val o = when (i.op) { "fadd" -> "+"; "fsub" -> "-"; "fmul" -> "*"; "fdiv" -> "/"; else -> "%" }
                    val e = "${w(op1(i, 0))}$o${w(op1(i, 1))}"
                    setResult(i, listOf(if (t == LFloat) "Math.fround($e)" else e))
                }
                "fneg" -> setResult(i, listOf("-${w(op1(i, 0))}"))
                "icmp" -> setResult(i, listOf(icmp(i.pred, i.ops[0].first, ex(i.ops[0].second, i.ops[0].first), ex(i.ops[1].second, i.ops[1].first))))
                "fcmp" -> { val a = w(op1(i, 0)); val b = w(op1(i, 1)); setResult(i, listOf(fcmp(i.pred, a, b))) }
                "select" -> {
                    val c0 = op1(i, 0); val xs = ex(i.ops[1].second, t); val ys = ex(i.ops[2].second, t)
                    val c = if (xs.size > 1) simple(c0) else c0
                    setResult(i, xs.indices.map { "${w(c)}?${xs[it]}:${ys[it]}" })
                }
                "trunc", "zext", "sext", "fptrunc", "fpext", "fptoui", "fptosi", "uitofp", "sitofp", "ptrtoint", "inttoptr", "bitcast", "addrspacecast", "freeze" ->
                    setResult(i, cast(i.op, i.ops[0].first, t, ex(i.ops[0].second, i.ops[0].first)))
                "getelementptr" -> setResult(i, listOf(gep(i)))
                "alloca" -> {
                    val off = allocaOff[i.res]
                    if (off != null) setResult(i, listOf(if (off == 0L) fp!! else "${fp!!}+$off|0"))
                    else {
                        val n = i.ops[0]; val cnt = ex(n.second, n.first)[0]
                        val size = sizeOf(i.srcTy)
                        emit("S=S-${w("Math.imul(${cnt},$size)")}&-16")
                        setResult(i, listOf("S"))
                    }
                }
                "load" -> {
                    val p = op1(i, 0)
                    val es = loadExpr(i.srcTy, if (parts(i.srcTy).size > 1) simple(p) else p, 0, i.align)
                    if (i.res in lazyHi) emit("${vals[i.res]!![0]}=${es[0]}") else setResult(i, es)
                }
                "store" -> {
                    val vt = i.ops[0].first; val es = ex(i.ops[0].second, vt); val p = op1(i, 1)
                    store(vt, if (es.size > 1) simple(p) else p, 0, i.align, es)
                }
                "extractvalue" -> {
                    val at = i.ops[0].first; val es = ex(i.ops[0].second, at)
                    var t2 = at; var start = 0
                    for (k in i.idx) when (val tt = t2) {
                        is LStruct -> { for (j in 0 until k) start += parts(tt.fields[j]).size; t2 = tt.fields[k] }
                        is LArr -> { start += k * parts(tt.elem).size; t2 = tt.elem }
                        else -> throw CompileError("JS backend: extractvalue")
                    }
                    setResult(i, es.subList(start, start + parts(t2).size))
                }
                "insertvalue" -> {
                    val at = i.ops[0].first; val es = ex(i.ops[0].second, at).toMutableList(); val vs = ex(i.ops[1].second, i.ops[1].first)
                    var t2 = at; var start = 0
                    for (k in i.idx) when (val tt = t2) {
                        is LStruct -> { for (j in 0 until k) start += parts(tt.fields[j]).size; t2 = tt.fields[k] }
                        is LArr -> { start += k * parts(tt.elem).size; t2 = tt.elem }
                        else -> throw CompileError("JS backend: insertvalue")
                    }
                    for (k in vs.indices) es[start + k] = vs[k]
                    setResult(i, es)
                }
                "call" -> genCall(i)
                "fence" -> {}
                "atomicrmw" -> {
                    val p = simple(op1(i, 0)); val vt = i.ops[1].first
                    val old = loadExpr(vt, p, 0, 0).map { simple(it) }
                    val v = ex(i.ops[1].second, vt)
                    val nv: List<String> = when (i.flag) {
                        "xchg" -> v
                        "add", "sub", "and", "or", "xor" -> {
                            val tmp = LlInst("\$rmw", i.flag, vt, listOf(vt to VLocal("\$o"), vt to VLocal("\$v")))
                            vals["\$o"] = old; vals["\$v"] = v
                            useCount["\$rmw"] = 1; vals.remove("\$rmw")
                            genInst(tmp); vals["\$rmw"]!!
                        }
                        else -> throw CompileError("JS backend: atomicrmw ${i.flag}")
                    }
                    store(vt, p, 0, 0, nv)
                    setResult(i, old)
                }
                "cmpxchg" -> {
                    val p = simple(op1(i, 0)); val vt = i.ops[1].first
                    val old = loadExpr(vt, p, 0, 0).map { simple(it) }
                    val c = ex(i.ops[1].second, vt); val nv = ex(i.ops[2].second, vt)
                    val ok = simple(old.indices.joinToString("&&") { "${old[it]}==${w(c[it])}" })
                    sb.append("if($ok){"); store(vt, p, 0, 0, nv); sb.append("}\n")
                    setResult(i, old + ok)
                }
                else -> throw CompileError("JS backend: unsupported instruction ${i.op} in @${f.name}")
            }
        }

        fun icmp(pred: String, t: LTy, a: List<String>, b: List<String>): String {
            if (isWide(t)) {
                val n = (t as LInt).bits
                val (x, y) = if (pred[0] == 's') "BigInt.asIntN($n,${a[0]})" to "BigInt.asIntN($n,${b[0]})" else w(a[0]) to w(b[0])
                val o = mapOf("eq" to "==", "ne" to "!=", "slt" to "<", "sle" to "<=", "sgt" to ">", "sge" to ">=", "ult" to "<", "ule" to "<=", "ugt" to ">", "uge" to ">=")[pred]
                    ?: throw CompileError("JS backend: icmp $pred")
                return "$x$o$y"
            }
            if (isI64(t)) {
                val (x, xh) = a; val (y, yh) = b
                val lo = mapOf("eq" to "==", "ne" to "!=", "slt" to "<", "sle" to "<=", "sgt" to ">", "sge" to ">=", "ult" to "<", "ule" to "<=", "ugt" to ">", "uge" to ">=")[pred]
                    ?: throw CompileError("JS backend: icmp $pred")
                val lows = if (pred == "eq" || pred == "ne") "${w(x)}$lo${w(y)}" else "(${w(x)}>>>0)$lo(${w(y)}>>>0)"
                // same high word (one variable or equal literals): the low words decide
                if (xh == yh && atom(xh)) return lows
                // one high word is 0 (indices, lengths below 2^32): a sign/zero test on the other one; a high word
                // still to be loaded (lazyHi) goes after the low words, which usually decide a bounds check
                if (pred[0] == 'u' && (xh == "0" || yh == "0")) {
                    val big = pred == "ugt" || pred == "uge"; val h = w(if (yh == "0") xh else yh)
                    return if ((yh == "0") != big) "!$h&&$lows" else if (atom(h)) "$h!=0||$lows" else "$lows||$h!=0"
                }
                if (y == "0" && yh == "0") when (pred) {
                    "eq" -> return "!(${w(x)}|${w(xh)})"
                    "ne" -> return "(${w(x)}|${w(xh)})!=0"
                    "slt" -> return "${w(xh)}<0"
                    "sge" -> return "${w(xh)}>=0"
                }
                if (pred == "eq") return "${w(x)}==${w(y)}&&${w(xh)}==${w(yh)}"
                if (pred == "ne") return "${w(x)}!=${w(y)}||${w(xh)}!=${w(yh)}"
                // the high words are read twice: an inlined load goes to a variable first
                fun once(e: String) = if (atom(e) || e.toLongOrNull() != null) e else simple(e)
                val xh1 = once(xh); val yh1 = once(yh)
                val o = lo; val s = o.substring(0, 1)
                return if (pred[0] == 's') "${w(xh1)}$s${w(yh1)}||${w(xh1)}==${w(yh1)}&&(${w(x)}>>>0)$o(${w(y)}>>>0)"
                    else "(${w(xh1)}>>>0)$s(${w(yh1)}>>>0)||${w(xh1)}==${w(yh1)}&&(${w(x)}>>>0)$o(${w(y)}>>>0)"
            }
            val b0 = bitsOf(t); val x = a[0]; val y = b[0]
            return when (pred) {
                "eq" -> "${w(x)}==${w(y)}"; "ne" -> "${w(x)}!=${w(y)}"
                "slt" -> "${w(x)}<${w(y)}"; "sle" -> "${w(x)}<=${w(y)}"; "sgt" -> "${w(x)}>${w(y)}"; "sge" -> "${w(x)}>=${w(y)}"
                "ult" -> "${w(u(x, b0))}<${w(u(y, b0))}"; "ule" -> "${w(u(x, b0))}<=${w(u(y, b0))}"
                "ugt" -> "${w(u(x, b0))}>${w(u(y, b0))}"; "uge" -> "${w(u(x, b0))}>=${w(u(y, b0))}"
                else -> throw CompileError("JS backend: icmp $pred")
            }
        }

        fun fcmp(p: String, a: String, b: String) = when (p) {
            "oeq" -> "$a==$b"; "ogt" -> "$a>$b"; "oge" -> "$a>=$b"; "olt" -> "$a<$b"; "ole" -> "$a<=$b"; "one" -> "$a<$b||$a>$b"
            "ord" -> "$a==$a&&$b==$b"; "ueq" -> "!($a<$b||$a>$b)"; "ugt" -> "!($a<=$b)"; "uge" -> "!($a<$b)"; "ult" -> "!($a>=$b)"
            "ule" -> "!($a>$b)"; "une" -> "$a!=$b"; "uno" -> "$a!=$a||$b!=$b"; "true" -> "true"; "false" -> "false"
            else -> throw CompileError("JS backend: fcmp $p")
        }

        fun cast(op: String, from: LTy, to: LTy, a: List<String>): List<String> {
            val x = a[0]
            if (isWide(from) || isWide(to)) return wideCast(op, from, to, a)
            return when (op) {
                "freeze", "bitcast", "addrspacecast" -> when {
                    from == to || (from == LPtr && to == LPtr) -> a
                    isI64(from) && to == LDouble -> listOf("_i2d(${a[0]},${a[1]})")
                    from == LDouble && isI64(to) -> { val v = newVar(); val h = newVar(); emit("$v=_d2i($x)"); emit("$h=H"); listOf(v, h) }
                    from is LInt && from.bits == 32 && to == LFloat -> listOf("_i2f($x)")
                    from == LFloat && to is LInt && to.bits == 32 -> listOf("_f2i($x)")
                    else -> throw CompileError("JS backend: bitcast $from to $to")
                }
                "trunc" -> { val b = bitsOf(to); if (b == 32) listOf(x) else listOf(norm(x, b)) }
                "zext" -> {
                    val fb = bitsOf(from)
                    val lo = when (fb) { 1 -> "+${w(x)}"; 8 -> "${w(x)}&255"; 16 -> "${w(x)}&65535"; else -> x }
                    if (isI64(to)) listOf(lo, "0") else listOf(lo)
                }
                "sext" -> {
                    val fb = bitsOf(from)
                    val lo = if (fb == 1) "-${w(x)}" else x
                    if (isI64(to)) { val s = simple(lo); listOf(s, "${w(s)}>>31") } else listOf(lo)
                }
                "ptrtoint" -> if (isI64(to)) listOf(x, "0") else listOf(if (bitsOf(to) == 32) x else norm(x, bitsOf(to)))
                "inttoptr" -> listOf(x)
                "fptrunc" -> listOf("Math.fround($x)")
                "fpext" -> listOf(x)
                "fptosi" -> if (isI64(to)) { val v = newVar(); val h = newVar(); emit("$v=_d2l($x)"); emit("$h=H"); listOf(v, h) } else listOf(norm(x, bitsOf(to)).let { if (bitsOf(to) == 32) "${w(x)}|0" else it })
                "fptoui" -> if (isI64(to)) { val v = newVar(); val h = newVar(); emit("$v=_d2ul($x)"); emit("$h=H"); listOf(v, h) } else listOf(if (bitsOf(to) == 32) "${w(x)}>>>0|0" else norm("${w(x)}>>>0", bitsOf(to)))
                "sitofp" -> { val e = if (isI64(from)) "${w(a[1])}*4294967296+(${w(x)}>>>0)" else x; listOf(if (to == LFloat) "Math.fround($e)" else e) }
                "uitofp" -> { val e = if (isI64(from)) "(${w(a[1])}>>>0)*4294967296+(${w(x)}>>>0)" else u(x, bitsOf(from)); listOf(if (to == LFloat) "Math.fround($e)" else e) }
                else -> throw CompileError("JS backend: cast $op")
            }
        }

        fun gep(i: LlInst): String {
            val base = op1(i, 0)
            var off = 0L; val terms = ArrayList<String>()
            var t: LTy = i.srcTy
            for (k in 1 until i.ops.size) {
                val (it0, iv) = i.ops[k]
                val size: Long
                if (k == 1) size = sizeOf(t)
                else when (val tt = t) {
                    is LStruct -> { val fi = constInt(iv).toInt(); off += fieldOffset(tt, fi); t = tt.fields[fi]; continue }
                    is LArr -> { size = sizeOf(tt.elem); t = tt.elem }
                    else -> throw CompileError("JS backend: gep into $t")
                }
                if (iv !is VLocal) { off += constInt(iv) * size; continue }
                val idx = ex(iv, it0)[0]
                terms.add(when {
                    size == 1L -> idx
                    size > 0 && (size and (size - 1)) == 0L && size < (1L shl 31) -> "${w(idx)}<<${63 - size.countLeadingZeroBits()}"
                    else -> "Math.imul($idx,$size)"
                })
            }
            val off32 = off.toInt()
            if (terms.isEmpty() && off32 == 0) return base
            val sbx = StringBuilder(w(base))
            for (tm in terms) sbx.append('+').append(w(tm))
            if (off32 != 0) sbx.append(if (off32 > 0) "+$off32" else "$off32")
            return "$sbx|0"
        }

        // ---- odd-width integers as BigInt -------------------------------------------------------------------------
        fun genWide(i: LlInst) {
            val n = (i.ty as LInt).bits
            val x = w(op1(i, 0)); val y = w(op1(i, 1))
            fun s(e: String) = "BigInt.asIntN($n,$e)"
            val e = when (i.op) {
                "add" -> "$x+$y"; "sub" -> "$x-$y"; "mul" -> "$x*$y"; "and" -> "$x&$y"; "or" -> "$x|$y"; "xor" -> "$x^$y"
                "shl" -> "$x<<$y"; "lshr" -> "$x>>$y"; "ashr" -> "${s(x)}>>$y"
                "udiv" -> "$x/$y"; "urem" -> "$x%$y"; "sdiv" -> "${s(x)}/${s(y)}"; "srem" -> "${s(x)}%${s(y)}"
                else -> throw CompileError("JS backend: i$n ${i.op}")
            }
            setResult(i, listOf("BigInt.asUintN($n,$e)"))
        }

        fun wideCast(op: String, from: LTy, to: LTy, a: List<String>): List<String> {
            val fb = bitsOf(from); val tb = bitsOf(to)
            val big = when {           // the source as a BigInt (signed for sext)
                isWide(from) -> if (op == "sext") "BigInt.asIntN($fb,${a[0]})" else a[0]
                isI64(from) -> if (op == "sext") "_big(${a[0]},${a[1]})" else "BigInt.asUintN(64,_big(${a[0]},${a[1]}))"
                op == "sext" -> "BigInt(${a[0]})"
                else -> "BigInt(${u(a[0], fb)})"
            }
            return when {
                op != "zext" && op != "sext" && op != "trunc" && op != "freeze" -> throw CompileError("JS backend: $op $from to $to")
                isWide(to) -> listOf("BigInt.asUintN($tb,$big)")
                isI64(to) -> { val v = newVar(); val h = newVar(); emit("$v=_unbig($big)"); emit("$h=H"); listOf(v, h) }
                else -> listOf(norm("Number(BigInt.asIntN(32,$big))", tb).let { if (tb == 32) "Number(BigInt.asIntN(32,$big))" else it })
            }
        }

        // ---- 64-bit integer arithmetic ----------------------------------------------------------------------------
        fun genI64(i: LlInst) {
            val (x, xh) = ex(i.ops[0].second, i.ops[0].first); val (y, yh) = ex(i.ops[1].second, i.ops[1].first)
            if (i.res == null || useCount[i.res] == null) return
            val (r, rh) = defineVars(i)
            when (i.op) {
                "add" -> { emit("$r=${w(x)}+${w(y)}|0"); emit("$rh=${w(xh)}${if (yh == "0") "" else "+" + w(yh)}+(($r>>>0)<(${w(x)}>>>0))|0") }
                "sub" -> { emit("$r=${w(x)}-${w(y)}|0"); emit("$rh=${w(xh)}${if (yh == "0") "" else "-" + w(yh)}-((${w(x)}>>>0)<(${w(y)}>>>0))|0") }
                "and", "or", "xor" -> { val o = mapOf("and" to "&", "or" to "|", "xor" to "^")[i.op]; emit("$r=${w(x)}$o${w(y)}"); emit("$rh=${w(xh)}$o${w(yh)}") }
                "mul" -> {
                    // by a constant 0 < c < 2^21: the low word times c is exact in a double
                    fun small(e: String, h: String) = h == "0" && e.toLongOrNull()?.let { it in 1 until (1L shl 21) } == true
                    val (v, vh, c) = if (small(y, yh)) Triple(x, xh, y) else if (small(x, xh)) Triple(y, yh, x) else Triple("", "", "")
                    if (c.isEmpty()) { emit("$r=_mul($x,$xh,$y,$yh)"); emit("$rh=H") }
                    else { val sv = simple(v); emit("$rh=Math.imul(${w(vh)},$c)+((${w(sv)}>>>0)*$c/4294967296|0)|0"); emit("$r=Math.imul($sv,$c)") }
                }
                "sdiv", "udiv", "srem", "urem" -> { emit("$r=_${i.op}($x,$xh,$y,$yh)"); emit("$rh=H") }
                "shl", "lshr", "ashr" -> {
                    val c = (i.ops[1].second as? VInt)?.v?.toInt()?.and(63)
                    if (c == null) { emit("$r=_${i.op}($x,$xh,$y)"); emit("$rh=H"); return }
                    when {
                        c == 0 -> { emit("$r=$x"); emit("$rh=$xh") }
                        i.op == "shl" && c < 32 -> { emit("$rh=${w(xh)}<<$c|${w(x)}>>>${32 - c}"); emit("$r=${w(x)}<<$c") }
                        i.op == "shl" -> { emit("$rh=${w(x)}<<${c - 32}"); emit("$r=0") }
                        i.op == "lshr" && c < 32 -> { emit("$r=${w(x)}>>>$c|${w(xh)}<<${32 - c}"); emit("$rh=${w(xh)}>>>$c") }
                        i.op == "lshr" -> { emit("$r=${w(xh)}>>>${c - 32}|0"); emit("$rh=0") }
                        c < 32 -> { emit("$r=${w(x)}>>>$c|${w(xh)}<<${32 - c}"); emit("$rh=${w(xh)}>>$c") }
                        else -> { emit("$r=${w(xh)}>>${c - 32}"); emit("$rh=${w(xh)}>>31") }
                    }
                }
            }
        }

        // ---- calls ----------------------------------------------------------------------------------------------------
        fun callResult(i: LlInst, call: String) {
            val n = parts(i.ty).size
            if (n == 0 || i.res == null || useCount[i.res] == null) { emit(call); return }
            val vs = defineVars(i)
            emit("${vs[0]}=$call")
            for (k in 1 until n) emit("${vs[k]}=${hName(k)}")
        }
        fun hName(k: Int) = if (k == 1) "H" else "H${k - 1}"

        fun genCall(i: LlInst) {
            val args = i.ops.flatMap { (t, v) -> ex(v, t) }
            val c = i.callee!!
            if (c is VGlobal) {
                val n = resolveAlias(c.name)
                if (n.startsWith("llvm.")) return intrinsic(i, n, args)
                val fn = m.funcs[n]
                if (fn != null) {
                    val js = if (fn.isDecl) externName(n) else jsName(n)
                    return callResult(i, "$js(${args.joinToString(",")})")
                }
            }
            val p = ex1(c, LPtr)
            callResult(i, "T[$p](${args.joinToString(",")})")
        }

        fun intrinsic(i: LlInst, n: String, a: List<String>) {
            val name = n.removePrefix("llvm.")
            val t = i.ty
            fun res(e: String) = setResultCall(i, listOf(e))
            fun res2(call: String) = callResult(i, call)
            val i64 = isI64(t) || (i.ops.isNotEmpty() && isI64(i.ops[0].first) && !name.startsWith("mem"))
            when {
                name.startsWith("memcpy") || name.startsWith("memmove") -> {
                    val d = a[0]; val s = a[1]; val len = (i.ops[2].second as? VInt)?.v
                    val al = minOf(i.argAlign.getOrElse(0) { 0 }, i.argAlign.getOrElse(1) { 0 })
                    // Small constant copies become loads and stores (TypedArray.copyWithin is a builtin call);
                    // all loads come first, so overlapping memmove is right too.
                    val unit = if (al >= 4 && len != null && len % 4 == 0L) 4 else 1
                    if (len != null && len > 0 && len <= (if (unit == 4) 32 else 8)) {
                        val ds = simple(d); val ss = simple(s)
                        val arr = if (unit == 4) "I32" else "U8"; val sh = if (unit == 4) ">>2" else ""
                        val tmps = (0 until len step unit.toLong()).map { k -> val t = newVar(); emit("$t=$arr[${w(ss)}+$k$sh]"); k to t }
                        for ((k, t) in tmps) emit("$arr[${w(ds)}+$k$sh]=$t")
                    } else emit("\$memcpy($d,$s,${a[2]})")
                }
                name.startsWith("memset") -> {
                    val d = simple(a[0]); val len = (i.ops[2].second as? VInt)?.v; val b = (i.ops[1].second as? VInt)?.v
                    val al = i.argAlign.getOrElse(0) { 0 }
                    if (len != null && b != null && len > 0 && al >= 4 && len % 4 == 0L && len <= 64) {
                        val word = (b and 255) * 0x01010101L
                        for (k in 0 until len step 4) emit("I32[${w(d)}+$k>>2]=${word.toInt()}")
                    } else if (len != null && len > 0 && len <= 8) for (k in 0 until len) emit("U8[${w(d)}+$k]=${a[1]}")
                    else emit("\$memset($d,${a[1]},${a[2]})")
                }
                name.startsWith("lifetime.") || name.startsWith("assume") || name.startsWith("dbg.") || name.startsWith("experimental.noalias") ||
                    name.startsWith("invariant.") || name.startsWith("sideeffect") || name.startsWith("donothing") || name.startsWith("var.annotation") -> {}
                name.startsWith("expect") || name.startsWith("threadlocal.address") || name.startsWith("launder.invariant") ||
                    name.startsWith("strip.invariant") || name.startsWith("ssa.copy") -> setResultCall(i, a.subList(0, parts(t).size))
                name.startsWith("frameaddress") -> res("_fa()")
                name.startsWith("stacksave") -> res(if (depthProbe) "_fa()" else "S")
                name.startsWith("stackrestore") -> emit("S=${a[0]}")
                name.startsWith("trap") || name.startsWith("ubsantrap") || name.startsWith("debugtrap") -> emit("_U()")
                name.startsWith("smax") || name.startsWith("smin") || name.startsWith("umax") || name.startsWith("umin") -> {
                    val k = name.substring(0, 4)
                    if (i64) res2("_$k(${a.joinToString(",")})")
                    else {
                        val b = bitsOf(t); val x = simple(a[0]); val y = simple(a[1])
                        val cmp = when (k) { "smax" -> "$x>$y"; "smin" -> "$x<$y"; "umax" -> "${w(u(x, b))}>${w(u(y, b))}"; else -> "${w(u(x, b))}<${w(u(y, b))}" }
                        res("$cmp?$x:$y")
                    }
                }
                name.startsWith("abs.") -> if (i64) res2("_abs(${a[0]},${a[1]})") else res("Math.abs(${a[0]})|0")
                name.startsWith("ctlz") -> if (i64) res2("_ctlz(${a[0]},${a[1]})") else res(if (bitsOf(t) == 32) "Math.clz32(${a[0]})" else "Math.clz32(${u(a[0], bitsOf(t))})-${32 - bitsOf(t)}")
                name.startsWith("cttz") -> if (i64) res2("_cttz(${a[0]},${a[1]})") else res("_cttz32(${a[0]})")
                name.startsWith("ctpop") -> if (i64) res2("_ctpop(${a[0]},${a[1]})") else res("_popc(${u(a[0], bitsOf(t))})")
                name.startsWith("bswap") -> if (i64) res2("_bswap64(${a[0]},${a[1]})") else res("_bswap32(${a[0]})" + if (bitsOf(t) == 16) ">>16" else "")
                name.startsWith("fshl") || name.startsWith("fshr") -> if (i64) res2("_${name.substring(0, 4)}64(${a.joinToString(",")})")
                    else if (bitsOf(t) == 32) res("_${name.substring(0, 4)}(${a.joinToString(",")})") else throw CompileError("JS backend: $n")
                name.startsWith("scmp") || name.startsWith("ucmp") -> {
                    val at = i.ops[0].first
                    if (isI64(at)) res2("_${name.substring(0, 4)}64(${a.joinToString(",")})")
                    else { val b = bitsOf(at); val x = simple(if (name[0] == 'u') u(a[0], b) else a[0]); val y = simple(if (name[0] == 'u') u(a[1], b) else a[1]); res("($x>$y)-($x<$y)") }
                }
                name.contains("with.overflow") -> {
                    val k = name.substringBefore(".with")
                    val at = i.ops[0].first
                    if (isI64(at)) { val vs = if (i.res != null && useCount[i.res] != null) defineVars(i) else listOf(newVar(), newVar(), newVar()); emit("${vs[0]}=_${k}o(${a.joinToString(",")})"); emit("${vs[1]}=H"); emit("${vs[2]}=H1"); return }
                    val b = bitsOf(at); if (b != 32) throw CompileError("JS backend: $n")
                    val x = simple(a[0]); val y = simple(a[1]); val r = newVar()
                    when (k) {
                        "sadd" -> { emit("$r=$x+$y|0"); setResultCall(i, listOf(r, "(($x^$r)&($y^$r))<0")) }
                        "uadd" -> { emit("$r=$x+$y|0"); setResultCall(i, listOf(r, "($r>>>0)<($x>>>0)")) }
                        "ssub" -> { emit("$r=$x-$y|0"); setResultCall(i, listOf(r, "(($x^$y)&($x^$r))<0")) }
                        "usub" -> { emit("$r=$x-$y|0"); setResultCall(i, listOf(r, "($x>>>0)<($y>>>0)")) }
                        "smul" -> { emit("$r=Math.imul($x,$y)"); setResultCall(i, listOf(r, "$r!=$x*$y")) }
                        "umul" -> { emit("$r=Math.imul($x,$y)"); setResultCall(i, listOf(r, "($x>>>0)*($y>>>0)>4294967295")) }
                        else -> throw CompileError("JS backend: $n")
                    }
                }
                name.startsWith("fptosi.sat") || name.startsWith("fptoui.sat") -> {
                    val s = name[4] == 's'
                    if (isI64(t)) res2(if (s) "_d2l(${a[0]})" else "_d2ul(${a[0]})")
                    else if (bitsOf(t) == 32) res(if (s) "_d2i32(${a[0]})" else "_d2u32(${a[0]})")
                    else throw CompileError("JS backend: $n")
                }
                else -> {
                    val f = name.substringBefore('.')
                    val fl = t == LFloat
                    val e = when (f) {
                        "sqrt", "floor", "ceil", "trunc", "exp", "log", "log2", "log10", "sin", "cos", "tan", "atan", "asin", "acos", "sinh", "cosh", "tanh", "exp2" ->
                            if (f == "exp2") "Math.pow(2,${a[0]})" else "Math.$f(${a[0]})"
                        "fabs" -> "Math.abs(${a[0]})"
                        "pow", "powi" -> "Math.pow(${a[0]},${a[1]})"
                        "round" -> "_round(${a[0]})"
                        "rint", "nearbyint", "roundeven" -> "_rint(${a[0]})"
                        "copysign" -> "_copysign(${a[0]},${a[1]})"
                        "minnum", "minimum" -> "Math.min(${a[0]},${a[1]})"
                        "maxnum", "maximum" -> "Math.max(${a[0]},${a[1]})"
                        "fma", "fmuladd" -> "${w(a[0])}*${w(a[1])}+${w(a[2])}"
                        else -> throw CompileError("JS backend: unsupported intrinsic @$n")
                    }
                    res(if (fl) "Math.fround($e)" else e)
                }
            }
        }

        /** Results of calls/intrinsics with expression values: never inlined (calls are side effects). */
        fun setResultCall(i: LlInst, es: List<String>) {
            val r = i.res ?: return
            if (useCount[r] == null) return
            if (r in inlineSet) { vals[r] = es; return }
            val vs = defineVars(i)
            for (k in vs.indices) emit("${vs[k]}=${es[k]}")
        }

        // ---- phi copies ------------------------------------------------------------------------------------------------
        fun phiCopies(from: Int, to: Int) {
            val src = blocks[from].name
            val ds = ArrayList<String>(); val ss = ArrayList<String>()
            for (p in blocks[to].insts) {
                if (p.op != "phi") break
                if (p.res == null || useCount[p.res] == null) continue
                val v = p.incoming.firstOrNull { it.second == src }?.first ?: throw CompileError("JS backend: phi without %$src in @${f.name}")
                val dv = vals[p.res]!!; val sv = ex(v, p.ty)
                for (k in dv.indices) if (dv[k] != sv[k]) { ds.add(dv[k]); ss.add(sv[k]) }
            }
            if (ds.isEmpty()) return
            val dset = ds.toHashSet()
            val conflict = ss.withIndex().any { (k, e) -> dset.any { d -> d != ds[k] && Regex("\\b${Regex.escape(d)}\\b").containsMatchIn(e) } }
            if (!conflict) { for (k in ds.indices) emit("${ds[k]}=${ss[k]}"); return }
            val tmps = ss.map { simple(it).let { s -> if (s in dset) { val t = newVar(); emit("$t=$s"); t } else s } }
            for (k in ds.indices) emit("${ds[k]}=${tmps[k]}")
        }

        // ---- structured control flow (Ramsey) ------------------------------------------------------------------------
        lateinit var rpo: IntArray      // block -> reverse postorder number
        lateinit var idom: IntArray
        lateinit var loopHeader: BooleanArray
        lateinit var merge: BooleanArray
        val domKids = HashMap<Int, ArrayList<Int>>()   // structuring tree: dominator tree with loop exits moved up
        val loopMem = HashMap<Int, BooleanArray>()

        fun cfg(): Boolean {
            val n = blocks.size
            val order = ArrayList<Int>(); val seen = BooleanArray(n)
            // iterative DFS postorder
            val stack = ArrayList<IntArray>(); stack.add(intArrayOf(0, 0)); seen[0] = true
            while (stack.isNotEmpty()) {
                val top = stack.last(); val ss = succ[top[0]]
                if (top[1] < ss.size) { val s = ss[top[1]++]; if (!seen[s]) { seen[s] = true; stack.add(intArrayOf(s, 0)) } }
                else { order.add(top[0]); stack.removeAt(stack.size - 1) }
            }
            order.reverse()
            rpo = IntArray(n) { -1 }; order.forEachIndexed { k, b -> rpo[b] = k }
            val preds = Array(n) { ArrayList<Int>() }
            for (b in order) for (s in succ[b]) preds[s].add(b)
            idom = IntArray(n) { -1 }; idom[0] = 0
            var changed = true
            fun intersect(a0: Int, b0: Int): Int { var a = a0; var b = b0; while (a != b) { while (rpo[a] > rpo[b]) a = idom[a]; while (rpo[b] > rpo[a]) b = idom[b] }; return a }
            while (changed) {
                changed = false
                for (b in order) { if (b == 0) continue
                    var nd = -1
                    for (p in preds[b]) if (idom[p] != -1) nd = if (nd == -1) p else intersect(p, nd)
                    if (nd != idom[b]) { idom[b] = nd; changed = true }
                }
            }
            fun dominates(a: Int, b0: Int): Boolean { var b = b0; while (true) { if (a == b) return true; if (b == 0) return false; b = idom[b] } }
            loopHeader = BooleanArray(n); merge = BooleanArray(n)
            val fwd = IntArray(n)
            for (b in order) for (s in succ[b]) {
                if (rpo[s] <= rpo[b]) { if (!dominates(s, b)) return false; loopHeader[s] = true } else fwd[s]++
            }
            for (b in order) merge[b] = fwd[b] >= 2
            // natural loop bodies: header -> member blocks
            for (b in order) for (s in succ[b]) if (rpo[s] <= rpo[b]) {
                val mem = loopMem.getOrPut(s) { BooleanArray(n).also { it[s] = true } }
                val work = ArrayList<Int>(); if (!mem[b]) { mem[b] = true; work.add(b) }
                while (work.isNotEmpty()) { val y = work.removeAt(work.size - 1); for (p in preds[y]) if (!mem[p]) { mem[p] = true; work.add(p) } }
            }
            // A loop exit goes after the loop, not inside its body (where Ramsey's dominator tree puts it): V8's OSR
            // code for an inner loop deoptimizes whenever control leaves that loop, so it must leave only by `break`.
            // Its parent becomes the outermost loop header that holds its dominator but not the exit itself.
            for (e in order) {
                if (e == 0) continue
                val d = idom[e]; var h = d; var best = -1
                while (true) { if (loopHeader[h] && loopMem[h]!![d] && !loopMem[h]!![e]) best = h; if (h == 0) break; h = idom[h] }
                if (best != -1) merge[e] = true
                domKids.getOrPut(if (best != -1) best else d) { ArrayList() }.add(e)
            }
            return true
        }

        fun doTree(x: Int, ctx: List<Ctx>) {
            val ms = (domKids[x] ?: emptyList<Int>()).filter { merge[it] }.sortedByDescending { rpo[it] }
            if (loopHeader[x]) { val mem = loopMem[x]!!; val (ins, outs) = ms.partition { mem[it] }; loopThen(x, outs, ins, ctx) }
            else nodeWithin(x, ms, ctx)
        }

        /** Loop x wrapped in labeled blocks for its exits outs (each one's code follows its block), merges ins inside. */
        fun loopThen(x: Int, outs: List<Int>, ins: List<Int>, ctx: List<Ctx>) {
            if (outs.isEmpty()) { sb.append("L$x:for(;;){\n"); nodeWithin(x, ins, listOf<Ctx>(Ctx.Loop(x)) + ctx); sb.append("}\n"); return }
            val y = outs[0]
            sb.append("B$y:{\n"); loopThen(x, outs.drop(1), ins, listOf<Ctx>(Ctx.Blk(y)) + ctx); sb.append("}\n")
            doTree(y, ctx)
        }

        fun nodeWithin(x: Int, ys: List<Int>, ctx: List<Ctx>) {
            if (ys.isEmpty()) { codeFor(x, ctx); return }
            val y = ys[0]
            sb.append("B$y:{\n"); nodeWithin(x, ys.drop(1), listOf<Ctx>(Ctx.Blk(y)) + ctx); sb.append("}\n")
            doTree(y, ctx)
        }

        fun doBranch(from: Int, to: Int, ctx: List<Ctx>) {
            phiCopies(from, to)
            when {
                rpo[to] <= rpo[from] -> emit("continue L$to")
                merge[to] -> emit("break B$to")
                else -> doTree(to, ctx)
            }
        }

        fun codeFor(x: Int, ctx: List<Ctx>) {
            val b = blocks[x]
            for (i in b.insts.dropLast(1)) if (i.op != "phi") genInst(i)
            terminator(x, b.insts.last()) { to -> doBranch(x, to, ctx) }
        }

        fun terminator(x: Int, i: LlInst, branch: (Int) -> Unit) {
            when (i.op) {
                "br" -> if (i.labels.size == 1) branch(bIndex[i.labels[0]]!!) else {
                    val c = op1(i, 0)
                    val a = bIndex[i.labels[0]]!!; val b = bIndex[i.labels[1]]!!
                    if (a == b) { branch(a); return }
                    sb.append("if(${c}){\n"); branch(a); sb.append("}\n"); branch(b)
                }
                "switch" -> {
                    val t = i.ops[0].first; val v = ex(i.ops[0].second, t)
                    val groups = LinkedHashMap<Int, ArrayList<Long>>()
                    for (k in i.cases.indices) groups.getOrPut(bIndex[i.labels[k + 1]]!!) { ArrayList() }.add(i.cases[k])
                    val def = bIndex[i.labels[0]]!!
                    groups.remove(def)
                    if (isI64(t)) {
                        val lo = simple(v[0]); val hi = simple(v[1])
                        for ((tgt, cs) in groups) {
                            sb.append("if(${cs.joinToString("||") { "$lo==${it.toInt()}&&$hi==${(it shr 32).toInt()}" }}){\n"); branch(tgt); sb.append("}\n")
                        }
                        branch(def); return
                    }
                    val sv = if (bitsOf(t) == 1) "+${w(v[0])}" else v[0]
                    if (groups.size <= 2) {
                        val s = simple(sv)
                        for ((tgt, cs) in groups) { sb.append("if(${cs.joinToString("||") { "$s==${it.toInt()}" }}){\n"); branch(tgt); sb.append("}\n") }
                        branch(def); return
                    }
                    sb.append("switch($sv){\n")
                    for ((tgt, cs) in groups) { for (c in cs) sb.append("case ${c.toInt()}:"); sb.append("{\n"); branch(tgt); sb.append("}\n") }
                    sb.append("}\n"); branch(def)
                }
                "ret" -> {
                    val es = if (i.ops.isEmpty()) emptyList() else ex(i.ops[0].second, i.ops[0].first)
                    val vs = if (es.size > 1) es.map { simple(it) } else es
                    for (k in 1 until vs.size) emit("${hName(k)}=${vs[k]}")
                    if (fp != null) emit("S=${w(fp!!)}+$frame|0")
                    if (depthProbe) emit("D=D-1|0")
                    emit(if (vs.isEmpty()) "return" else "return ${vs[0]}")
                }
                "unreachable" -> emit("_U()")
                else -> throw CompileError("JS backend: terminator ${i.op}")
            }
        }

        // ---- fallback: dispatch loop for irreducible control flow ---------------------------------------------------
        fun dispatch() {
            val st = newVar()
            sb.append("$st=0;\nD:for(;;)switch($st){\n")
            for ((x, b) in blocks.withIndex()) {
                sb.append("case $x:{\n")
                for (i in b.insts.dropLast(1)) if (i.op != "phi") genInst(i)
                terminator(x, b.insts.last()) { to -> phiCopies(x, to); emit("$st=$to"); emit("continue D") }
                sb.append("}\n")
            }
            sb.append("}\n")
        }

        fun gen(): String {
            val ps = f.params.flatMap { p -> parts(p.ty).map { newVar() }.also { vals[p.name] = it } }
            declared.clear() // parameters are not declared with let
            analyze()
            // every used, non-inlined result gets its variable up front (the dispatch fallback may emit a use first)
            for (b in blocks) for (i in b.insts) { val r = i.res ?: continue; if (useCount[r] != null && r !in inlineSet && r !in lazyHi) defineVars(i) }
            for (b in blocks) for (i in b.insts) if (i.res in lazyHi) vals[i.res!!] = listOf(newVar(), loadExpr(i.srcTy, op1(i, 0), 0, i.align)[1])
            if (depthProbe) emit("D=D+1|0")
            if (fp != null) emit("${fp}=S=S-$frame|0")
            val reducible = cfg()
            if (reducible) doTree(0, emptyList()) else { warn("irreducible control flow in @${f.name}: dispatch loop"); dispatch() }
            val nl = if (compact) "" else "\n"
            val head = StringBuilder("function ${jsName(f.name)}(${ps.joinToString(",")}){$nl")
            // no initializers: every read of an SSA value is dominated by its assignment
            if (declared.isNotEmpty()) head.append("let ").append(declared.joinToString(",")).append(";$nl")
            return head.append(if (compact) sb.toString().replace("\n", "") else sb).append("}\n").toString()
        }
    }
}

/** Enclosing construct of a structured block: a loop (`continue Lx`) or a labeled block (`break By`). */
private sealed class Ctx { class Loop(val b: Int) : Ctx(); class Blk(val b: Int) : Ctx() }
