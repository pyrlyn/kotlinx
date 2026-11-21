package newkotlin.jvm

import newkotlin.CompileError

/*
 * Jvm target: LLVM IR -> JVM bytecode.
 *
 * The frontend and NativeBackend are shared with the native target: the program's LLVM IR, the C runtime and the
 * collector are linked and optimized by LLVM (see driver/JvmLink.kt), and this module translates the optimized
 * module into one JVM class. Memory stays a flat native address space (pointers are `long`s, accessed through
 * jdk.internal.misc.Unsafe, which the JIT intrinsifies); libc calls go to the small Java runtime KxRt
 * (compiler/runtime/jvm/KxRt.java). SSA values become JVM locals, phis become parallel copies on CFG edges.
 *
 * Value representation: i1 -> int 0/1; i2..i32 -> int, sign-extended; i33..i64 -> long, sign-extended; ptr -> long;
 * float/double -> float/double. Function pointers are small integer ids dispatched by generated `icall$N` methods.
 */

const val KXRT = "KxRt"
private const val UNSAFE = "jdk/internal/misc/Unsafe"
private const val UNSAFE_D = "Ljdk/internal/misc/Unsafe;"

/** Constant-size llvm.memcpy/memmove/memset up to this many bytes are lowered to inline Unsafe get/put chunks. */
private const val INLINE_MEM = 64L

/** FILE* handles of the C standard streams (must match KxRt.java). */
private val STD_STREAMS = mapOf("__stdinp" to 1L, "stdin" to 1L, "__stdoutp" to 2L, "stdout" to 2L, "__stderrp" to 3L, "stderr" to 3L)

/** Symbolic constant: address of [global] + [add], or the absolute value [add] when [global] is null. */
private class Sym(val global: String?, val add: Long)

class JvmBackend(private val ir: String, private val className: String, private val runtimeFuncs: Set<String>,
                 private val outlineLoops: Boolean = true) {
    private lateinit var m: LModule
    private val cb = ClassBuilder(className, interfaces = listOf("java/lang/Runnable"))
    private val cp get() = cb.cp
    private val jname = HashMap<String, String>()           // LLVM function -> JVM method name
    private val fnId = LinkedHashMap<String, Int>()          // address-taken function -> id
    private val gOff = HashMap<String, Long>()               // global -> offset in the globals block
    private var gSize = 0L
    private val icalls = LinkedHashMap<String, String>()     // descriptor -> dispatcher name
    private val extWrappers = LinkedHashMap<String, String>() // external function used as a value -> wrapper name
    /** Bounds checks become Preconditions.checkIndex, reported by run() through rt_oob (needs rt_oob(i64, i64)). */
    private var oobHandler = false

    private fun err(msg: String): Nothing = throw CompileError("jvm: $msg")

    /** Class file bytes of the translated program. */
    fun generate(): ByteArray {
        m = parseLlvmIr(ir)
        val outliner = LoopOutliner(m)
        if (outlineLoops) outliner.run()
        oobHandler = m.funcs["rt_oob"]?.let { f ->
            !f.isDecl && f.ret.r() === VoidT && f.params.size == 2 && f.params.all { (it.r() as? IntT)?.bits == 64 }
        } == true
        nameFunctions()
        collectFunctionIds()
        val ctors = layoutGlobals()
        for (f in m.funcs.values) if (!f.isDecl) translate(f)
        for ((desc, name) in icalls.entries.toList()) dispatcher(desc, name)
        for ((ext, w) in extWrappers) wrapper(ext, w)
        clinit(); entry(ctors)
        cb.field(ACC_PUBLIC or ACC_STATIC or ACC_FINAL, "G", "J")
        for ((n, t) in outliner.fields) cb.field(ACC_PRIVATE or ACC_STATIC, n, kind(t).toString())
        return cb.bytes()
    }

    // ------------------------------------------------------------ names, ids

    private fun nameFunctions() {
        val used = hashSetOf("main", "run", "<init>", "<clinit>", "G")
        for (f in m.funcs.values) {
            if (f.isDecl) continue
            var n = f.name.map { if (it.isLetterOrDigit() || it == '_' || it == '$') it else '_' }.joinToString("")
            if (n.isEmpty() || n[0].isDigit()) n = "_$n"
            if (n == "main") n = "kx_main"
            var k = n; var s = 1
            while (!used.add(k)) k = "$n$${s++}"
            jname[f.name] = k
        }
    }

    private fun cName(n: String) = n.removePrefix("\u0001").removePrefix("_").let { if (n.startsWith("\u0001")) it else n }.substringBefore('$')

    private fun isFunc(name: String) = m.funcs.containsKey(name) || (m.aliases[name] as? GlobalRef)?.let { m.funcs.containsKey(it.name) } == true

    private fun collectFunctionIds() {
        fun visit(v: LValue, callee: Boolean) {
            when (v) {
                is GlobalRef -> if (!callee) {
                    val n = (m.aliases[v.name] as? GlobalRef)?.name ?: v.name
                    if (m.funcs.containsKey(n) && !n.startsWith("llvm.")) fnId.getOrPut(n) { fnId.size + 1 }
                }
                is CAgg -> v.elems.forEach { visit(it.v, false) }
                is CExpr -> v.args.forEach { visit(it.v, false) }
                else -> {}
            }
        }
        for (g in m.globals.values) if (g.name != "llvm.global_ctors") g.init?.let { visit(it, false) }
        for (f in m.funcs.values) for (b in f.blocks) for (i in b.insts) {
            i.ops.forEach { visit(it.v, false) }
            i.incoming.forEach { visit(it.first, false) }
            i.callee?.let { if (it !is GlobalRef) visit(it, false) }
        }
    }

    // ------------------------------------------------------------ globals

    /** Lays out all globals in one native block; returns the constructors from llvm.global_ctors. */
    private fun layoutGlobals(): List<String> {
        val ctors = ArrayList<Pair<Long, String>>()
        for (g in m.globals.values) {
            if (g.name == "llvm.global_ctors") {
                (g.init as? CAgg)?.elems?.forEach { e -> val f = (e.v as CAgg).elems; ctors.add((f[0].v as CInt).v to (f[1].v as GlobalRef).name) }
                continue
            }
            if (g.name.startsWith("llvm.")) continue
            if (g.init == null && g.name !in STD_STREAMS) err("external global @${g.name} is not available on the jvm target")
            gSize = alignUp(gSize, maxOf(g.align, alignOf(g.ty), 1))
            gOff[g.name] = gSize
            gSize += maxOf(sizeOf(g.ty), 1)
        }
        gSize = alignUp(gSize, 16)
        return ctors.sortedBy { it.first }.map { it.second }
    }

    private fun sym(tv: TV): Sym = when (val v = tv.v) {
        is CInt -> Sym(null, v.v)
        CZero -> Sym(null, 0)
        is GlobalRef -> {
            val n = (m.aliases[v.name] as? GlobalRef)?.name ?: v.name
            when {
                gOff.containsKey(n) -> Sym(n, 0)
                fnId.containsKey(n) -> Sym(null, fnId[n]!!.toLong())
                m.aliases.containsKey(n) -> sym(TV(tv.t, m.aliases[n]!!))
                else -> err("unknown global @$n")
            }
        }
        is CExpr -> when (v.op) {
            "getelementptr" -> { val b = sym(v.args[0]); var off = b.add; off += gepConst(v.srcElem!!, v.args.drop(1)); Sym(b.global, off) }
            "bitcast", "addrspacecast", "inttoptr" -> sym(v.args[0])
            "ptrtoint" -> sym(v.args[0])
            "trunc" -> { val s = sym(v.args[0]); if (s.global != null) err("truncated address constant"); Sym(null, truncTo(s.add, (v.to!!.r() as IntT).bits)) }
            "add" -> { val a = sym(v.args[0]); val b = sym(v.args[1]); if (a.global != null && b.global != null) err("sum of addresses"); Sym(a.global ?: b.global, a.add + b.add) }
            "sub" -> {
                val a = sym(v.args[0]); val b = sym(v.args[1])
                when {
                    b.global == null -> Sym(a.global, a.add - b.add)
                    a.global != null -> Sym(null, gOff[a.global]!! + a.add - gOff[b.global]!! - b.add)
                    else -> err("negated address constant")
                }
            }
            "icmp" -> Sym(null, if (icmpConst(v.pred!!, v.args[0], v.args[1])) 1 else 0)
            else -> err("unsupported constant expression ${v.op}")
        }
        else -> err("unsupported constant $v")
    }

    /**
     * Folds an `icmp` constant expression. All globals live in one block, so two addresses in it compare like their
     * offsets, and an address in it differs from every constant (null, a function id).
     */
    private fun icmpConst(pred: String, a: TV, b: TV): Boolean {
        val x = sym(a); val y = sym(b)
        val cmp = when {
            x.global != null && y.global != null -> (gOff[x.global]!! + x.add).compareTo(gOff[y.global]!! + y.add)
            x.global != null || y.global != null ->
                if (pred == "eq" || pred == "ne") return pred == "ne" else err("ordered comparison of an address with a constant")
            pred[0] == 'u' -> { val n = bits(a.t); val m = if (n >= 64) -1L else (1L shl n) - 1; (x.add and m).toULong().compareTo((y.add and m).toULong()) }
            else -> { val n = bits(a.t); truncTo(x.add, n).compareTo(truncTo(y.add, n)) }
        }
        return when (pred) {
            "eq" -> cmp == 0; "ne" -> cmp != 0
            "ult", "slt" -> cmp < 0; "ule", "sle" -> cmp <= 0
            "ugt", "sgt" -> cmp > 0; "uge", "sge" -> cmp >= 0
            else -> err("unknown icmp predicate $pred")
        }
    }

    private fun truncTo(v: Long, bits: Int) = if (bits >= 64) v else (v shl (64 - bits)) shr (64 - bits)

    private fun gepConst(src: LType, idx: List<TV>): Long {
        var off = 0L
        var t = src
        idx.forEachIndexed { k, tv ->
            val i = sym(tv).also { if (it.global != null) err("address used as an index") }.add
            if (k == 0) off += i * sizeOf(t)
            else when (val r = t.r()) {
                is StructT -> { off += fieldOffset(r, i.toInt()); t = r.fields[i.toInt()] }
                is ArrayT -> { off += i * sizeOf(r.elem); t = r.elem }
                is VecT -> { off += i * sizeOf(r.elem); t = r.elem }
                else -> err("bad getelementptr into $r")
            }
        }
        return off
    }

    /** Initial bytes of the globals block (relative addresses) and the offsets holding addresses to relocate. */
    private fun globalData(): Pair<ByteArray, List<Long>> {
        val data = ByteArray(gSize.toInt())
        val relocs = ArrayList<Long>()
        fun putLE(at: Long, v: Long, n: Int) { for (k in 0 until n) data[(at + k).toInt()] = (v shr (8 * k)).toByte() }
        fun write(at: Long, tv: TV) {
            val t = tv.t.r()
            when (val v = tv.v) {
                CZero -> {}
                is CBytes -> v.bytes.copyInto(data, at.toInt())
                is CFloat -> if (t === FloatT) putLE(at, v.v.toFloat().toRawBits().toLong(), 4) else putLE(at, v.v.toRawBits(), 8)
                is CAgg -> when (t) {
                    is StructT -> v.elems.forEachIndexed { k, e -> write(at + fieldOffset(t, k), e) }
                    is ArrayT -> v.elems.forEachIndexed { k, e -> write(at + k * sizeOf(t.elem), e) }
                    is VecT -> v.elems.forEachIndexed { k, e -> write(at + k * sizeOf(t.elem), e) }
                    else -> err("aggregate constant of type $t")
                }
                else -> {
                    val s = sym(tv)
                    val n = if (t is IntT) ((t.bits + 7) / 8) else 8
                    if (s.global != null) {
                        if (n != 8) err("address constant narrower than a pointer")
                        putLE(at, gOff[s.global]!! + s.add, 8); relocs.add(at)
                    } else putLE(at, s.add, n)
                }
            }
        }
        for (g in m.globals.values) {
            val off = gOff[g.name] ?: continue
            val init = g.init ?: TV(PtrT, CInt(STD_STREAMS[g.name]!!)).v
            write(off, TV(g.ty, init))
        }
        return data to relocs
    }

    private fun clinit() {
        val c = Code(cp, false)
        c.lconst(gSize); c.static(KXRT, "globals", "(J)J"); c.field(Op.PUTSTATIC, className, "G", "J")
        val (data, relocs) = globalData()
        var at = 0
        while (at < data.size) {
            while (at < data.size && data[at].toInt() == 0) at++
            if (at >= data.size) break
            val end = minOf(at + 16000, data.size)
            var last = end; while (last > at && data[last - 1].toInt() == 0) last--
            val s = CharArray(last - at) { (data[at + it].toInt() and 0xFF).toChar() }.concatToString()
            c.field(Op.GETSTATIC, className, "G", "J"); c.lconst(at.toLong()); c.op(Op.LADD, -2)
            c.sconst(s); c.static(KXRT, "data", "(JLjava/lang/String;)V")
            at = end
        }
        for (part in relocs.chunked(8000)) {
            val s = StringBuilder(); for (r in part) { s.append((r ushr 16).toInt().toChar()); s.append((r and 0xFFFF).toInt().toChar()) }
            c.field(Op.GETSTATIC, className, "G", "J"); c.sconst(s.toString()); c.static(KXRT, "reloc", "(JLjava/lang/String;)V")
        }
        c.op(Op.RETURN, 0)
        cb.method(ACC_STATIC, "<clinit>", "()V", c)
    }

    /** main(String[]) starts KxRt on a thread with a large stack; run() calls the constructors and C main. */
    private fun entry(ctors: List<String>) {
        var c = Code(cp, false); c.maxLocals = 1
        c.load('A', 0); c.invoke(Op.INVOKESPECIAL, "java/lang/Object", "<init>", "()V"); c.op(Op.RETURN, 0)
        cb.method(ACC_PUBLIC, "<init>", "()V", c)
        c = Code(cp, false); c.maxLocals = 1
        c.new(className); c.op(Op.DUP, 1); c.invoke(Op.INVOKESPECIAL, className, "<init>", "()V")
        c.load('A', 0); c.static(KXRT, "start", "(Ljava/lang/Runnable;[Ljava/lang/String;)V"); c.op(Op.RETURN, 0)
        cb.method(ACC_PUBLIC or ACC_STATIC, "main", "([Ljava/lang/String;)V", c)
        c = Code(cp, false); c.maxLocals = 1
        val start = Label()
        c.place(start)
        for (k in ctors) { val f = m.funcs[k] ?: err("unknown constructor @$k"); c.static(className, jname[k]!!, desc(f.type)) }
        val main = m.funcs["main"] ?: err("no main function")
        when (main.params.size) {
            0 -> {}
            2 -> { c.static(KXRT, "argc", "()I"); c.static(KXRT, "argv", "()J") }
            else -> err("unsupported main signature")
        }
        c.static(className, jname["main"]!!, desc(main.type))
        if (main.ret === VoidT) c.iconst(0)
        // C exit() (compiler/runtime/jvm/libc.c) runs the atexit handlers, e.g. the --leak-check report.
        val exit = m.funcs["exit"]?.takeIf { !it.isDecl }
        if (exit != null) c.static(className, jname["exit"]!!, desc(exit.type)) else c.static(KXRT, "exit", "(I)V")
        c.op(Op.RETURN, 0)
        if (oobHandler) {
            // a failed bounds check (see boundsCheck): report it with the program's rt_oob, which exits
            val end = Label(); val h = Label()
            c.place(end); c.place(h); c.stack = 1
            c.op(Op.POP, -1)
            c.field(Op.GETSTATIC, KXRT, "oobIndex", "J"); c.field(Op.GETSTATIC, KXRT, "oobLength", "J")
            c.static(className, jname["rt_oob"]!!, "(JJ)V")
            c.op(Op.RETURN, 0)
            c.handlers.add(Code.Handler(start, end, h, "$KXRT\$Oob"))
        }
        cb.method(ACC_PUBLIC, "run", "()V", c)
    }

    // ------------------------------------------------------------ types

    private fun kind(t: LType): Char = when (val r = t.r()) {
        is IntT -> if (r.bits <= 32) 'I' else if (r.bits <= 64) 'J' else err("i${r.bits} is not supported")
        PtrT -> 'J'; FloatT -> 'F'; DoubleT -> 'D'
        else -> err("no JVM type for $r")
    }
    /** i65..i128: LLVM's closed forms (SCEV binomials) compute in iW and truncate back. Such values get no local:
     *  a trunc to <= 64 bits evaluates their expression tree as (lo, hi) long pairs (pushWide). */
    private fun wide(t: LType) = (t.r() as? IntT)?.bits?.let { it > 64 } == true
    private fun bits(t: LType): Int = when (val r = t.r()) { is IntT -> r.bits; PtrT -> 64; else -> err("not an integer: $r") }
    private fun isAgg(t: LType) = t.r().let { it is StructT || it is ArrayT }
    private fun desc(f: FuncT, extraVarargs: Boolean = false): String =
        "(" + f.params.joinToString("") { kind(it).toString() } + (if (extraVarargs) "[J" else "") + ")" + (if (f.ret.r() === VoidT) "V" else kind(f.ret))

    /** Scalar leaves of an aggregate, with their byte offsets. */
    private fun leaves(t: LType, base: Long = 0, out: MutableList<Pair<LType, Long>> = ArrayList()): MutableList<Pair<LType, Long>> {
        when (val r = t.r()) {
            is StructT -> r.fields.forEachIndexed { k, f -> leaves(f, base + fieldOffset(r, k), out) }
            is ArrayT -> for (k in 0 until r.n) leaves(r.elem, base + k * sizeOf(r.elem), out)
            else -> out.add(r to base)
        }
        return out
    }
    /** First leaf index and leaf count of the member at [path]. */
    private fun leafRange(t: LType, path: IntArray): Pair<Int, Int> {
        var start = 0; var cur = t.r()
        for (k in path) {
            when (cur) {
                is StructT -> { for (j in 0 until k) start += leaves(cur.fields[j]).size; cur = cur.fields[k].r() }
                is ArrayT -> { start += k * leaves(cur.elem).size; cur = cur.elem.r() }
                else -> err("bad aggregate index")
            }
        }
        return start to leaves(cur).size
    }

    // ------------------------------------------------------------ function translation

    private fun translate(f: LFunc) {
        if (f.varargs) err("variadic function @${f.name} cannot be defined on the jvm target")
        val code = try { FnGen(f, false).gen() } catch (e: BranchTooFar) { FnGen(f, true).gen() }
        cb.method(ACC_PUBLIC or ACC_STATIC, jname[f.name]!!, desc(f.type), code)
    }

    private inner class FnGen(val f: LFunc, wide: Boolean) {
        val c = Code(cp, wide)
        val slot = HashMap<String, Int>()
        val aggSlots = HashMap<String, IntArray>()
        val aggType = HashMap<String, LType>()
        val labels = HashMap<String, Label>()
        val blockOf = HashMap<String, Block>()
        val defs = HashMap<String, Inst>()
        val uses = HashMap<String, Int>()
        var nextSlot = 0
        var savedSp = -1; var frame = -1
        val allocaOff = HashMap<Inst, Long>()
        var curBlock: Block? = null

        fun newSlot(k: Char): Int { val s = nextSlot; nextSlot += if (k == 'J' || k == 'D') 2 else 1; return s }

        fun gen(): Code {
            f.params.forEachIndexed { k, t -> slot[f.paramNames[k]] = newSlot(kind(t)) }
            for (b in f.blocks) { labels[b.name] = Label(); blockOf[b.name] = b }
            var frameSize = 0L
            var needSp = false
            for ((bi, b) in f.blocks.withIndex()) for (i in b.insts) {
                i.res?.let { r ->
                    defs[r] = i
                    if (i.ty !== VoidT) {
                        if (isAgg(i.ty)) { aggType[r] = i.ty; aggSlots[r] = leaves(i.ty).map { newSlot(kind(it.first)) }.toIntArray() }
                        else if (wide(i.ty)) { if (bits(i.ty) > 128) err("i${bits(i.ty)} is not supported") }
                        else slot[r] = newSlot(kind(i.ty))
                    }
                }
                fun use(v: LValue) { if (v is Local) uses[v.name] = (uses[v.name] ?: 0) + 1 }
                i.ops.forEach { use(it.v) }; i.incoming.forEach { use(it.first) }; i.callee?.let { use(it) }
                if (i.op == "alloca") {
                    needSp = true
                    if (bi == 0 && (i.ops.isEmpty() || i.ops[0].v is CInt)) {
                        val n = if (i.ops.isEmpty()) 1 else (i.ops[0].v as CInt).v
                        val a = maxOf(alignOf(i.srcElem!!), 8)
                        frameSize = alignUp(frameSize, a); allocaOff[i] = frameSize; frameSize += sizeOf(i.srcElem!!) * n
                    }
                }
                if (i.op == "call" && (i.callee as? GlobalRef)?.name?.let { it.startsWith("llvm.stacksave") || it.startsWith("llvm.stackrestore") } == true) needSp = true
            }
            if (needSp) {
                savedSp = newSlot('J'); frame = newSlot('J')
                c.field(Op.GETSTATIC, KXRT, "SP", "J"); c.op(Op.DUP2, 2); c.store('J', savedSp)
                c.lconst(alignUp(frameSize, 16)); c.op(Op.LSUB, -2); c.op(Op.DUP2, 2)
                c.field(Op.PUTSTATIC, KXRT, "SP", "J"); c.store('J', frame)
            }
            for ((k, b) in f.blocks.withIndex()) {
                curBlock = b
                c.place(labels[b.name]!!); c.stack = 0
                val next = f.blocks.getOrNull(k + 1)
                for (i in b.insts) inst(i, b, next)
            }
            c.maxLocals = nextSlot
            return c
        }

        // ---- operands

        fun s(name: String) = slot[name] ?: err("@${f.name}: unknown value %$name")

        fun push(tv: TV) {
            val t = tv.t.r()
            when (val v = tv.v) {
                is Local -> {
                    if (wide(t)) err("@${f.name}: %${v.name}: $t values are only supported when truncated back to <= 64 bits")
                    c.load(kind(t), s(v.name))
                }
                is CInt -> pushInt(t, v.v)
                is CFloat -> if (t === FloatT) c.fconst(v.v.toFloat()) else c.dconst(v.v)
                CZero -> zero(t)
                is GlobalRef, is CExpr -> {
                    if (v is GlobalRef && extern(v.name)) { c.lconst(extId(v.name)); return }
                    val sy = sym(tv)
                    if (sy.global != null) {
                        c.field(Op.GETSTATIC, className, "G", "J")
                        val off = gOff[sy.global]!! + sy.add
                        if (off != 0L) { c.lconst(off); c.op(Op.LADD, -2) }
                    } else c.lconst(sy.add)
                    if (kind(t) == 'I') { c.op(Op.L2I, -1); norm(t) }
                }
                else -> err("unsupported operand $v")
            }
        }
        fun extern(n: String) = m.funcs[n]?.isDecl == true && !n.startsWith("llvm.")
        fun extId(n: String): Long {
            fnId[n]?.let { return it.toLong() }
            val id = fnId.size + 1; fnId[n] = id; return id.toLong()
        }
        fun pushInt(t: LType, v: Long) = when (kind(t)) {
            'I' -> { val b = bits(t); c.iconst(if (b == 1) (v and 1).toInt() else truncTo(v, b).toInt()) }
            else -> c.lconst(truncTo(v, bits(t)))
        }
        fun zero(t: LType) = when (kind(t)) { 'I' -> c.iconst(0); 'J' -> c.lconst(0); 'F' -> c.fconst(0f); else -> c.dconst(0.0) }

        /** Re-establishes the representation of an iN value on the stack after an operation that may overflow N bits. */
        fun norm(t: LType) {
            val b = bits(t)
            if (kind(t) == 'I') when (b) {
                32 -> {}; 1 -> { c.iconst(1); c.op(Op.IAND, -1) }; 8 -> c.op(Op.I2B, 0); 16 -> c.op(Op.I2S, 0)
                else -> { c.iconst(32 - b); c.op(Op.ISHL, -1); c.iconst(32 - b); c.op(Op.ISHR, -1) }
            } else if (b < 64) { c.iconst(64 - b); c.op(Op.LSHL, -1); c.iconst(64 - b); c.op(Op.LSHR, -1) }
        }
        /** Zero-extends the iN value on the stack within its JVM type (unsigned view). */
        fun zext(t: LType) {
            val b = bits(t)
            if (kind(t) == 'I') { if (b in 2..31) { c.iconst(((1L shl b) - 1).toInt()); c.op(Op.IAND, -1) } }
            else if (b < 64) { c.lconst((1L shl b) - 1); c.op(Op.LAND, -2) }
        }
        fun storeRes(i: Inst) { c.store(kind(i.ty), s(i.res!!)) }
        fun pop(t: LType) { if (t.r() !== VoidT) c.op(if (kind(t) == 'J' || kind(t) == 'D') Op.POP2 else Op.POP, if (kind(t) == 'J' || kind(t) == 'D') -2 else -1) }

        // ---- aggregates (SSA values of struct type: one JVM local per scalar leaf)

        fun pushLeaf(tv: TV, k: Int) {
            val lv = leaves(tv.t)
            when (val v = tv.v) {
                is Local -> c.load(kind(lv[k].first), (aggSlots[v.name] ?: err("unknown aggregate %${v.name}"))[k])
                CZero -> zero(lv[k].first)
                is CAgg -> {
                    var acc = 0
                    val r = tv.t.r()
                    for ((j, e) in v.elems.withIndex()) {
                        val n = leaves(e.t).size
                        if (k < acc + n) { if (n == 1 && !isAgg(e.t)) push(e) else pushLeaf(e, k - acc); return }
                        acc += n
                        if (r is ArrayT && j < 0) break
                    }
                    err("bad aggregate constant")
                }
                else -> err("unsupported aggregate operand $v")
            }
        }

        // ---- control flow

        fun label(n: String) = labels[n] ?: err("@${f.name}: unknown block %$n")

        /** Phi copies for the edge [from] -> [to] (parallel: all values are pushed before any is stored). */
        fun copies(from: Block, to: String): Boolean {
            val tb = blockOf[to]!!
            val pairs = ArrayList<Pair<Inst, LValue>>()
            for (phi in tb.insts) {
                if (phi.op != "phi") break
                val v = phi.incoming.firstOrNull { it.second == from.name }?.first ?: err("@${f.name}: phi %${phi.res} has no value for %${from.name}")
                if (v is Local && v.name == phi.res) continue
                pairs.add(phi to v)
            }
            if (pairs.isEmpty()) return false
            for ((phi, v) in pairs) {
                if (isAgg(phi.ty)) for (k in leaves(phi.ty).indices) pushLeaf(TV(phi.ty, v), k) else push(TV(phi.ty, v))
            }
            for ((phi, _) in pairs.asReversed()) {
                if (isAgg(phi.ty)) { val sl = aggSlots[phi.res]!!; val lv = leaves(phi.ty); for (k in lv.indices.reversed()) c.store(kind(lv[k].first), sl[k]) }
                else storeRes(phi)
            }
            return true
        }
        fun hasCopies(from: Block, to: String) = blockOf[to]!!.insts.any { p -> p.op == "phi" && p.incoming.any { it.second == from.name && !(it.first is Local && (it.first as Local).name == p.res) } }

        fun goto(from: Block, to: String, next: Block?) {
            copies(from, to)
            if (next?.name != to) { c.jump(Op.GOTO, label(to)); }
        }

        /**
         * A Kotlin bounds check: `br (icmp ult idx, len), ok, bad` (or an equivalent predicate) where `bad` only calls
         * rt_oob(idx, len). Returns (idx, len, ok); the check is then emitted as Preconditions.checkIndex, which C2
         * eliminates or hoists out of loops (an explicit unsigned compare is kept in every iteration).
         * checkIndex also fails for a negative length, which rt_oob's length (an array size) never is.
         */
        fun boundsCheck(br: Inst, b: Block): Triple<TV, TV, String>? {
            if (!oobHandler) return null
            val v = br.ops[0].v as? Local ?: return null
            val cmp = defs[v.name]?.takeIf { it.op == "icmp" && uses[v.name] == 1 && b.insts.contains(it) } ?: return null
            if (cmp.ops[0].t.r() !is IntT || kind(cmp.ops[0].t) != 'J') return null
            val (idx, len, okOnTrue) = when (cmp.pred) {
                "ult" -> Triple(cmp.ops[0], cmp.ops[1], true); "ugt" -> Triple(cmp.ops[1], cmp.ops[0], true)
                "uge" -> Triple(cmp.ops[0], cmp.ops[1], false); "ule" -> Triple(cmp.ops[1], cmp.ops[0], false)
                else -> return null
            }
            val bad = blockOf[br.labels[if (okOnTrue) 1 else 0]]!!.insts
            if (bad.size != 2 || bad[1].op != "unreachable") return null
            val call = bad[0]
            if (call.op != "call" || (call.callee as? GlobalRef)?.name != "rt_oob" || call.ops.size != 2) return null
            fun same(a: LValue, x: LValue) = (a is Local && x is Local && a.name == x.name) || (a is CInt && x is CInt && a.v == x.v)
            if (!same(call.ops[0].v, idx.v) || !same(call.ops[1].v, len.v)) return null
            return Triple(idx, len, br.labels[if (okOnTrue) 0 else 1])
        }

        /** Jumps to [target] when the i1 condition [cond] (negated if [neg]) holds; falls through otherwise. */
        fun condJump(cond: TV, target: Label, neg: Boolean) {
            val v = cond.v
            val def = (v as? Local)?.let { defs[it.name] }
            if (def != null && (def.op == "icmp" || def.op == "fcmp") && uses[v.name] == 1 && curBlock!!.insts.contains(def)) {
                if (def.op == "icmp") icmpJump(def, target, neg) else fcmpJump(def, target, neg); return
            }
            if (v is CInt) { if ((v.v != 0L) != neg) c.jump(Op.GOTO, target); return }
            push(cond); c.jump(if (neg) Op.IFEQ else Op.IFNE, target, -1)
        }

        fun icmpJump(i: Inst, target: Label, neg: Boolean) {
            val inv = mapOf("eq" to "ne", "ne" to "eq", "slt" to "sge", "sge" to "slt", "sle" to "sgt", "sgt" to "sle",
                "ult" to "uge", "uge" to "ult", "ule" to "ugt", "ugt" to "ule")
            val p = if (neg) inv[i.pred]!! else i.pred
            val a = i.ops[0]; val b = i.ops[1]; val t = a.t
            // samesign: unsigned and signed order agree, and a signed compare keeps loops counted for C2
            val unsigned = p[0] == 'u' && !i.samesign
            val signedP = if (p[0] == 'u') "s" + p.substring(1) else p
            val ifOp = mapOf("eq" to Op.IFEQ, "ne" to Op.IFNE, "slt" to Op.IFLT, "sge" to Op.IFGE, "sgt" to Op.IFGT, "sle" to Op.IFLE)[signedP]!!
            if (kind(t) == 'I') {
                val bz = (b.v is CInt && (b.v as CInt).v == 0L) || b.v === CZero
                if (bz && !unsigned) { push(a); c.jump(ifOp, target, -1); return }
                if (unsigned && bits(t) == 32) {
                    push(a); c.iconst(Int.MIN_VALUE); c.op(Op.IXOR, -1); push(b); c.iconst(Int.MIN_VALUE); c.op(Op.IXOR, -1)
                } else if (unsigned) { push(a); zext(t); push(b); zext(t) }
                else { push(a); push(b) }
                c.jump(ifOp - Op.IFEQ + Op.IF_ICMPEQ, target, -2)
            } else {
                push(a); if (unsigned && bits(t) < 64) zext(t)
                push(b); if (unsigned && bits(t) < 64) zext(t)
                if (unsigned && bits(t) == 64) c.static("java/lang/Long", "compareUnsigned", "(JJ)I") else c.op(Op.LCMP, -3)
                c.jump(ifOp, target, -1)
            }
        }

        fun fcmpJump(i: Inst, target: Label, neg: Boolean) {
            val inv = mapOf("oeq" to "une", "une" to "oeq", "ogt" to "ule", "ule" to "ogt", "oge" to "ult", "ult" to "oge",
                "olt" to "uge", "uge" to "olt", "ole" to "ugt", "ugt" to "ole", "one" to "ueq", "ueq" to "one",
                "ord" to "uno", "uno" to "ord", "true" to "false", "false" to "true")
            val p = if (neg) inv[i.pred]!! else i.pred
            val a = i.ops[0]; val b = i.ops[1]
            val d = kind(a.t) == 'D'
            val cmpl = if (d) Op.DCMPL else Op.FCMPL; val cmpg = if (d) Op.DCMPG else Op.FCMPG
            val w = if (d) -3 else -1
            fun cmp(x: TV, y: TV, o: Int, ifo: Int, l: Label) { push(x); push(y); c.op(o, w); c.jump(ifo, l, -1) }
            when (p) {
                "oeq" -> cmp(a, b, cmpl, Op.IFEQ, target); "ogt" -> cmp(a, b, cmpl, Op.IFGT, target)
                "oge" -> cmp(a, b, cmpl, Op.IFGE, target); "olt" -> cmp(a, b, cmpg, Op.IFLT, target)
                "ole" -> cmp(a, b, cmpg, Op.IFLE, target); "une" -> cmp(a, b, cmpl, Op.IFNE, target)
                "ugt" -> cmp(a, b, cmpg, Op.IFGT, target); "uge" -> cmp(a, b, cmpg, Op.IFGE, target)
                "ult" -> cmp(a, b, cmpl, Op.IFLT, target); "ule" -> cmp(a, b, cmpl, Op.IFLE, target)
                "one" -> { cmp(a, b, cmpg, Op.IFLT, target); cmp(a, b, cmpl, Op.IFGT, target) }
                "ueq" -> { val skip = Label(); cmp(a, b, cmpg, Op.IFLT, skip); cmp(a, b, cmpl, Op.IFGT, skip); c.jump(Op.GOTO, target); c.place(skip) }
                "uno" -> { cmp(a, a, cmpl, Op.IFNE, target); cmp(b, b, cmpl, Op.IFNE, target) }
                "ord" -> { val skip = Label(); cmp(a, a, cmpl, Op.IFNE, skip); cmp(b, b, cmpl, Op.IFNE, skip); c.jump(Op.GOTO, target); c.place(skip) }
                "true" -> c.jump(Op.GOTO, target)
                "false" -> {}
                else -> err("fcmp ${i.pred}")
            }
        }

        /** Materializes an i1 condition as 0/1 on the stack. */
        fun materialize(i: Inst) {
            val t = Label(); val e = Label()
            if (i.op == "icmp") icmpJump(i, t, false) else fcmpJump(i, t, false)
            c.iconst(0); c.jump(Op.GOTO, e); c.adj(-1); c.place(t); c.iconst(1); c.place(e)
        }

        // ---- instructions

        fun inst(i: Inst, b: Block, next: Block?) {
            when (i.op) {
                "phi" -> {}
                "br" -> if (i.labels.size == 1) goto(b, i.labels[0], next) else {
                    boundsCheck(i, b)?.let { (idx, len, ok) ->
                        push(idx); push(len); c.field(Op.GETSTATIC, KXRT, "OOB", "L$KXRT\$Oob;")
                        c.static("jdk/internal/util/Preconditions", "checkIndex", "(JJLjava/util/function/BiFunction;)J")
                        c.op(Op.POP2, -2)
                        goto(b, ok, next); return
                    }
                    val tl = i.labels[0]; val fl = i.labels[1]
                    when {
                        !hasCopies(b, tl) -> { condJump(i.ops[0], label(tl), false); goto(b, fl, next) }
                        !hasCopies(b, fl) -> { condJump(i.ops[0], label(fl), true); goto(b, tl, next) }
                        else -> { val lt = Label(); condJump(i.ops[0], lt, false); goto(b, fl, null); c.place(lt); goto(b, tl, next) }
                    }
                }
                "switch" -> switch(i, b)
                "ret" -> {
                    if (savedSp >= 0) { c.load('J', savedSp); c.field(Op.PUTSTATIC, KXRT, "SP", "J") }
                    if (i.ops.isEmpty()) c.op(Op.RETURN, 0)
                    else {
                        if (isAgg(i.ops[0].t)) err("@${f.name}: returning an aggregate is not supported")
                        push(i.ops[0])
                        c.op(when (kind(i.ops[0].t)) { 'I' -> Op.IRETURN; 'J' -> Op.LRETURN; 'F' -> Op.FRETURN; else -> Op.DRETURN }, 0)
                    }
                    c.stack = 0
                }
                "unreachable" -> { c.static(KXRT, "unreachable", "()Ljava/lang/RuntimeException;"); c.op(Op.ATHROW, -1); c.stack = 0 }
                "icmp", "fcmp" -> {
                    // fused into the block's branch when that is its only use
                    val n = i.res!!
                    val br = b.insts.last()
                    if (uses[n] == 1 && br.op == "br" && br.ops.firstOrNull()?.v.let { it is Local && it.name == n }) return
                    if (uses[n] == 1 && b.insts.any { it.op == "select" && it.ops[0].v.let { v -> v is Local && v.name == n } }) return
                    materialize(i); storeRes(i)
                }
                "select" -> {
                    if (isAgg(i.ty)) err("aggregate select")
                    val lb = Label(); val le = Label()
                    condJump(i.ops[0], lb, true)
                    push(i.ops[1]); c.jump(Op.GOTO, le)
                    c.adj(if (kind(i.ty) == 'J' || kind(i.ty) == 'D') -2 else -1)
                    c.place(lb); push(i.ops[2]); c.place(le); storeRes(i)
                }
                "add", "sub", "mul", "sdiv", "srem", "udiv", "urem", "shl", "lshr", "ashr", "and", "or", "xor" -> { if (wide(i.ty)) return; intOp(i.op, i.ty, i.ops[0], i.ops[1]); storeRes(i) }
                "fadd", "fsub", "fmul", "fdiv", "frem" -> {
                    push(i.ops[0]); push(i.ops[1])
                    val base = when (i.op) { "fadd" -> Op.FADD; "fsub" -> Op.FSUB; "fmul" -> Op.FMUL; "fdiv" -> Op.FDIV; else -> Op.FREM }
                    if (kind(i.ty) == 'D') c.op(base + 1, -2) else c.op(base, -1)
                    storeRes(i)
                }
                "fneg" -> { push(i.ops[0]); c.op(if (kind(i.ty) == 'D') Op.DNEG else Op.FNEG, 0); storeRes(i) }
                "freeze" -> { if (isAgg(i.ty)) err("aggregate freeze"); if (wide(i.ty)) return; push(i.ops[0]); storeRes(i) }
                "alloca" -> {
                    val off = allocaOff[i]
                    if (off != null) { c.load('J', frame); if (off != 0L) { c.lconst(off); c.op(Op.LADD, -2) } }
                    else {
                        c.field(Op.GETSTATIC, KXRT, "SP", "J")
                        if (i.ops.isEmpty()) c.lconst(sizeOf(i.srcElem!!)) else { push(i.ops[0]); toLong(i.ops[0].t); c.lconst(sizeOf(i.srcElem!!)); c.op(Op.LMUL, -2) }
                        c.op(Op.LSUB, -2); c.lconst(-16); c.op(Op.LAND, -2); c.op(Op.DUP2, 2); c.field(Op.PUTSTATIC, KXRT, "SP", "J")
                    }
                    storeRes(i)
                }
                "load" -> {
                    if (isAgg(i.ty)) {
                        val sl = aggSlots[i.res]!!
                        for ((k, lf) in leaves(i.ty).withIndex()) { loadAt(lf.first, i.ops[0], lf.second); c.store(kind(lf.first), sl[k]) }
                    } else { loadAt(i.ty, i.ops[0], 0); storeRes(i) }
                }
                "store" -> {
                    val v = i.ops[0]
                    if (isAgg(v.t)) for ((k, lf) in leaves(v.t).withIndex()) storeAt(lf.first, i.ops[1], lf.second) { pushLeaf(v, k) }
                    else storeAt(v.t, i.ops[1], 0) { push(v) }
                }
                "getelementptr" -> { gep(i); storeRes(i) }
                in setOf("trunc", "zext", "sext", "fptrunc", "fpext", "fptoui", "fptosi", "uitofp", "sitofp", "ptrtoint", "inttoptr", "bitcast", "addrspacecast") -> {
                    if (wide(i.ty)) { if (i.op != "zext" && i.op != "sext") err("@${f.name}: ${i.op} to ${i.ty}"); return }
                    cast(i.op, i.ops[0], i.ty); storeRes(i)
                }
                "call" -> call(i)
                "extractvalue" -> {
                    val (st, n) = leafRange(i.ops[0].t, i.indices)
                    if (isAgg(i.ty)) { val sl = aggSlots[i.res]!!; val lv = leaves(i.ty); for (k in 0 until n) { pushLeaf(i.ops[0], st + k); c.store(kind(lv[k].first), sl[k]) } }
                    else { pushLeaf(i.ops[0], st); storeRes(i) }
                }
                "insertvalue" -> {
                    val (st, n) = leafRange(i.ty, i.indices)
                    val sl = aggSlots[i.res]!!; val lv = leaves(i.ty)
                    for (k in lv.indices) {
                        if (k in st until st + n) { if (n == 1 && !isAgg(i.ops[1].t)) push(i.ops[1]) else pushLeaf(i.ops[1], k - st) }
                        else pushLeaf(i.ops[0], k)
                        c.store(kind(lv[k].first), sl[k])
                    }
                }
                "atomicrmw" -> {
                    val t = i.ty
                    loadAt(t, i.ops[0], 0); storeRes(i)
                    storeAt(t, i.ops[0], 0) {
                        val res = TV(t, Local(i.res!!))
                        when (i.pred) {
                            "xchg" -> push(i.ops[1])
                            "add", "sub", "and", "or", "xor" -> intOp(i.pred, t, res, i.ops[1])
                            else -> err("atomicrmw ${i.pred}")
                        }
                    }
                }
                "cmpxchg" -> {
                    val t = i.ops[1].t; val sl = aggSlots[i.res]!!
                    loadAt(t, i.ops[0], 0); c.store(kind(t), sl[0])
                    val no = Label(); val end = Label()
                    val eq = Inst(null, "icmp").also { it.pred = "ne"; it.ops.add(TV(t, Local("\u0000old"))); it.ops.add(i.ops[1]) }
                    slot["\u0000old"] = sl[0]
                    icmpJump(eq, no, false)
                    storeAt(t, i.ops[0], 0) { push(i.ops[2]) }
                    c.iconst(1); c.jump(Op.GOTO, end); c.adj(-1); c.place(no); c.iconst(0); c.place(end); c.store('I', sl[1])
                }
                "fence" -> {}
                // live-out values of an outlined loop (LoopOutline.kt): static fields of the class
                "kx.put" -> { push(i.ops[0]); c.field(Op.PUTSTATIC, className, i.pred, kind(i.ops[0].t).toString()) }
                "kx.get" -> { c.field(Op.GETSTATIC, className, i.pred, kind(i.ty).toString()); storeRes(i) }
                else -> err("@${f.name}: unsupported instruction ${i.op}")
            }
        }

        fun toLong(t: LType) { if (kind(t) == 'I') c.op(Op.I2L, 1) }

        fun intOp(op: String, t: LType, a: TV, b: TV) {
            val k = kind(t); val n = bits(t); val j = k == 'J'
            fun o(io: Int, lo: Int) = if (j) c.op(lo, -2) else c.op(io, -1)
            when (op) {
                "add" -> { push(a); push(b); o(Op.IADD, Op.LADD); norm(t) }
                "sub" -> { push(a); push(b); o(Op.ISUB, Op.LSUB); norm(t) }
                "mul" -> { push(a); push(b); o(Op.IMUL, Op.LMUL); norm(t) }
                "and" -> { push(a); push(b); o(Op.IAND, Op.LAND) }
                "or" -> { push(a); push(b); o(Op.IOR, Op.LOR) }
                "xor" -> { push(a); push(b); o(Op.IXOR, Op.LXOR) }
                "sdiv" -> { push(a); push(b); o(Op.IDIV, Op.LDIV); norm(t) }
                "srem" -> { push(a); push(b); o(Op.IREM, Op.LREM) }
                "udiv", "urem" -> {
                    val div = op == "udiv"
                    if (n == 32 || n == 64) {
                        push(a); push(b)
                        c.static(if (j) "java/lang/Long" else "java/lang/Integer", if (div) "divideUnsigned" else "remainderUnsigned", if (j) "(JJ)J" else "(II)I")
                    } else { push(a); zext(t); push(b); zext(t); if (div) o(Op.IDIV, Op.LDIV) else o(Op.IREM, Op.LREM); norm(t) }
                }
                "shl" -> { push(a); push(b); if (j) c.op(Op.L2I, -1); if (j) c.op(Op.LSHL, -1) else c.op(Op.ISHL, -1); norm(t) }
                "ashr" -> { push(a); push(b); if (j) c.op(Op.L2I, -1); if (j) c.op(Op.LSHR, -1) else c.op(Op.ISHR, -1) }
                "lshr" -> {
                    push(a); zext(t); push(b); if (j) c.op(Op.L2I, -1)
                    if (j) c.op(Op.LUSHR, -1) else c.op(Op.IUSHR, -1)
                    if (n != 32 && n != 64) norm(t)
                }
                else -> err("int op $op")
            }
        }

        fun unsafeGet(t: LType): Pair<String, String> = when (val r = t.r()) {
            is IntT -> when { r.bits <= 8 -> "getByte" to "(J)B"; r.bits <= 16 -> "getShort" to "(J)S"; r.bits <= 32 -> "getInt" to "(J)I"; r.bits <= 64 -> "getLong" to "(J)J"; else -> err("i${r.bits} load") }
            PtrT -> "getLong" to "(J)J"; FloatT -> "getFloat" to "(J)F"; DoubleT -> "getDouble" to "(J)D"
            else -> err("load of $r")
        }

        fun addr(p: TV, off: Long) { push(p); if (off != 0L) { c.lconst(off); c.op(Op.LADD, -2) } }

        fun loadAt(t: LType, p: TV, off: Long) {
            val r = t.r()
            // iN with an odd width (e.g. i2 coroutine states) occupies its store size, (N + 7) / 8 bytes.
            if (r is IntT && (r.bits + 7) / 8 !in setOf(1, 2, 4, 8)) err("load of i${r.bits}")
            c.field(Op.GETSTATIC, KXRT, "U", UNSAFE_D); addr(p, off)
            val (name, d) = unsafeGet(t)
            c.invoke(Op.INVOKEVIRTUAL, UNSAFE, name, d)
            if (r is IntT && r.bits == 1) { c.iconst(1); c.op(Op.IAND, -1) }
            else if (r is IntT && r.bits !in setOf(8, 16, 32, 64)) norm(t)
        }

        fun storeAt(t: LType, p: TV, off: Long, value: () -> Unit) {
            val r = t.r()
            if (r is IntT && (r.bits + 7) / 8 !in setOf(1, 2, 4, 8)) err("store of i${r.bits}")
            c.field(Op.GETSTATIC, KXRT, "U", UNSAFE_D); addr(p, off); value()
            val (name, d) = when (r) {
                is IntT -> when { r.bits <= 8 -> "putByte" to "(JB)V"; r.bits <= 16 -> "putShort" to "(JS)V"; r.bits <= 32 -> "putInt" to "(JI)V"; else -> "putLong" to "(JJ)V" }
                PtrT -> "putLong" to "(JJ)V"; FloatT -> "putFloat" to "(JF)V"; DoubleT -> "putDouble" to "(JD)V"
                else -> err("store of $r")
            }
            c.invoke(Op.INVOKEVIRTUAL, UNSAFE, name, d)
        }

        fun gep(i: Inst) {
            push(i.ops[0])
            var t = i.srcElem!!
            var constOff = 0L
            for ((k, tv) in i.ops.drop(1).withIndex()) {
                val size: Long
                if (k == 0) size = sizeOf(t)
                else when (val r = t.r()) {
                    is StructT -> { val fi = (tv.v as CInt).v.toInt(); constOff += fieldOffset(r, fi); t = r.fields[fi]; continue }
                    is ArrayT -> { size = sizeOf(r.elem); t = r.elem }
                    is VecT -> { size = sizeOf(r.elem); t = r.elem }
                    else -> err("getelementptr into $r")
                }
                val v = tv.v
                if (v is CInt || v === CZero) { constOff += (if (v is CInt) truncTo(v.v, bits(tv.t)) else 0L) * size; continue }
                push(tv); toLong(tv.t)
                if (size != 1L) {
                    if (size > 0 && (size and (size - 1)) == 0L) { c.iconst(size.countTrailingZeroBits()); c.op(Op.LSHL, -1) }
                    else { c.lconst(size); c.op(Op.LMUL, -2) }
                }
                c.op(Op.LADD, -2)
            }
            if (constOff != 0L) { c.lconst(constOff); c.op(Op.LADD, -2) }
        }

        /** Pushes a wide iW value as (lo, hi), zero-extended to W bits; KxRt.w* helpers return lo and leave hi in KxRt.wh. */
        fun pushWide(tv: TV) {
            val w = bits(tv.t)
            fun hi() = c.field(Op.GETSTATIC, KXRT, "wh", "J")
            when (val v = tv.v) {
                is CInt -> { c.lconst(v.v); c.lconst(v.v shr 63); c.iconst(w); c.static(KXRT, "wfix", "(JJI)J"); hi() }
                is Local -> {
                    val d = defs[v.name] ?: err("@${f.name}: unknown value %${v.name}")
                    when (d.op) {
                        "zext", "sext" -> {
                            val x = d.ops[0]
                            if (wide(x.t)) err("@${f.name}: ${d.op} ${x.t} to ${d.ty}")
                            cast(d.op, x, IntT(64))
                            if (d.op == "zext") { c.lconst(0); c.iconst(w); c.static(KXRT, "wfix", "(JJI)J") }
                            else { c.iconst(w); c.static(KXRT, "wsext", "(JI)J") }
                            hi()
                        }
                        "freeze" -> pushWide(d.ops[0])
                        "add", "sub", "mul", "shl", "lshr", "ashr", "and", "or", "xor" -> {
                            pushWide(d.ops[0]); pushWide(d.ops[1]); c.iconst(w)
                            c.static(KXRT, "w" + d.op, "(JJJJI)J"); hi()
                        }
                        else -> err("@${f.name}: ${d.op} on ${d.ty} is not supported")
                    }
                }
                else -> err("@${f.name}: unsupported ${tv.t} operand $v")
            }
        }

        fun cast(op: String, a: TV, to: LType) {
            val from = a.t
            if (op == "trunc" && wide(from)) {
                pushWide(a); c.op(Op.POP2, -2)
                if (kind(to) == 'I') c.op(Op.L2I, -1)
                norm(to); return
            }
            push(a)
            val fk = kind(from); val tk = kind(to)
            when (op) {
                "trunc" -> { if (fk == 'J' && tk == 'I') c.op(Op.L2I, -1); norm(to) }
                "zext" -> { zext(from); if (fk == 'I' && tk == 'J') { if (bits(from) == 32) { c.op(Op.I2L, 1); c.lconst(0xFFFFFFFFL); c.op(Op.LAND, -2) } else c.op(Op.I2L, 1) } }
                "sext" -> { if (bits(from) == 1) c.op(Op.INEG, 0); if (fk == 'I' && tk == 'J') c.op(Op.I2L, 1) }
                "fptrunc" -> c.op(Op.D2F, -1)
                "fpext" -> c.op(Op.F2D, 1)
                "fptosi" -> { conv(fk, if (tk == 'J') 'J' else 'I'); if (tk == 'I') norm(to) }
                "fptoui" -> {
                    if (tk == 'J' && bits(to) == 64) c.static(KXRT, "fptoui64", "(${if (fk == 'F') "F" else "D"})J")
                    else if (tk == 'J' || bits(to) == 32) { conv(fk, 'J'); if (tk == 'I') { c.op(Op.L2I, -1) } else norm(to) }
                    else { conv(fk, 'I'); norm(to) }
                }
                "sitofp" -> conv(fk, tk)
                "uitofp" -> {
                    if (fk == 'J' && bits(from) == 64) c.static(KXRT, "uitofp64", "(J)" + tk)
                    else { zext(from); if (fk == 'I' && bits(from) == 32) { c.op(Op.I2L, 1); c.lconst(0xFFFFFFFFL); c.op(Op.LAND, -2); conv('J', tk) } else conv(fk, tk) }
                }
                "ptrtoint" -> { if (tk == 'I') { c.op(Op.L2I, -1); norm(to) } else norm(to) }
                "inttoptr" -> { if (fk == 'I') { zext(from); c.op(Op.I2L, 1); if (bits(from) == 32) { c.lconst(0xFFFFFFFFL); c.op(Op.LAND, -2) } } }
                "bitcast", "addrspacecast" -> when {
                    fk == tk -> {}
                    fk == 'I' && tk == 'F' -> c.static("java/lang/Float", "intBitsToFloat", "(I)F")
                    fk == 'F' && tk == 'I' -> c.static("java/lang/Float", "floatToRawIntBits", "(F)I")
                    fk == 'J' && tk == 'D' -> c.static("java/lang/Double", "longBitsToDouble", "(J)D")
                    fk == 'D' && tk == 'J' -> c.static("java/lang/Double", "doubleToRawLongBits", "(D)J")
                    else -> err("bitcast $from to $to")
                }
                else -> err("cast $op")
            }
        }

        /** Primitive conversion between JVM kinds (I, J, F, D). */
        fun conv(from: Char, to: Char) {
            if (from == to) return
            val o = when ("$from$to") {
                "IJ" -> Op.I2L; "IF" -> Op.I2F; "ID" -> Op.I2D; "JI" -> Op.L2I; "JF" -> Op.L2F; "JD" -> Op.L2D
                "FI" -> Op.F2I; "FJ" -> Op.F2L; "FD" -> Op.F2D; "DI" -> Op.D2I; "DJ" -> Op.D2L; "DF" -> Op.D2F
                else -> err("conversion $from -> $to")
            }
            val w = { k: Char -> if (k == 'J' || k == 'D') 2 else 1 }
            c.op(o, w(to) - w(from))
        }

        fun switch(i: Inst, b: Block) {
            val v = i.ops[0]; val t = v.t
            val dflt = i.labels[0]
            val edge = HashMap<String, Label>()
            fun target(n: String): Label = if (hasCopies(b, n)) edge.getOrPut(n) { Label() } else label(n)
            val keys = i.cases.map { truncTo(it.first, bits(t)) }
            val dl = target(dflt)
            if (kind(t) == 'J') {
                if (keys.all { it in Int.MIN_VALUE..Int.MAX_VALUE }) {
                    push(v); push(v); c.op(Op.L2I, -1); c.op(Op.I2L, 1); c.op(Op.LCMP, -3); c.jump(Op.IFNE, dl, -1)
                    push(v); c.op(Op.L2I, -1)
                } else {
                    for ((k, cs) in i.cases.withIndex()) { push(v); c.lconst(keys[k]); c.op(Op.LCMP, -3); c.jump(Op.IFEQ, target(cs.second), -1) }
                    c.jump(Op.GOTO, dl)
                    emitEdges(b, edge); return
                }
            } else push(v)
            val ik = keys.map { it.toInt() }.toIntArray()
            val ts = i.cases.map { target(it.second) }
            if (ik.isEmpty()) { c.op(Op.POP, -1); c.jump(Op.GOTO, dl) }
            else {
                val lo = ik.min(); val hi = ik.max()
                if (hi.toLong() - lo < ik.size * 3L + 8) {
                    val table = ArrayList<Label>()
                    for (x in lo..hi) { val k = ik.indexOf(x); table.add(if (k >= 0) ts[k] else dl) }
                    c.tableswitch(lo, table, dl)
                } else c.lookupswitch(ik, ts, dl)
            }
            emitEdges(b, edge)
        }

        fun emitEdges(b: Block, edge: Map<String, Label>) {
            for ((to, l) in edge) { c.place(l); c.stack = 0; copies(b, to); c.jump(Op.GOTO, label(to)) }
            c.stack = 0
        }

        // ---- calls

        fun call(i: Inst) {
            val callee = i.callee!!
            val fnTy = i.fnTy!!
            if (callee is GlobalRef) {
                val name = (m.aliases[callee.name] as? GlobalRef)?.name ?: callee.name
                if (name.startsWith("llvm.")) { intrinsic(i, name); return }
                val f = m.funcs[name] ?: err("call of unknown function @$name")
                if (!f.isDecl) {
                    i.ops.forEach { push(it) }
                    c.static(className, jname[name]!!, desc(f.type))
                } else { external(i, name, f); return }
            } else {
                // indirect call through a function id
                if (fnTy.varargs) err("@${f.name}: indirect variadic call")
                push(TV(PtrT, callee))
                i.ops.forEach { push(it) }
                val d = desc(fnTy)
                val disp = icalls.getOrPut(d) { "icall$${icalls.size}" }
                c.static(className, disp, "(J" + d.substring(1))
            }
            result(i)
        }

        fun result(i: Inst) {
            if (i.ty.r() === VoidT) return
            if (i.res == null) pop(i.ty) else storeRes(i)
        }

        fun external(i: Inst, name: String, f: LFunc) {
            val cn = cName(name)
            if (cn !in runtimeFuncs) err("external function $cn (called from @${this.f.name}) is not available on the jvm target")
            val va = f.varargs || i.fnTy!!.varargs
            val fixed = f.params.size
            for ((k, a) in i.ops.withIndex()) if (k < fixed) push(a)
            if (va) {
                val extra = i.ops.drop(fixed)
                c.iconst(extra.size); c.op(Op.NEWARRAY, 0); c.b.u1(11) // T_LONG
                for ((k, a) in extra.withIndex()) {
                    c.op(Op.DUP, 1); c.iconst(k); push(a)
                    when (kind(a.t)) {
                        'I' -> c.op(Op.I2L, 1)
                        'F' -> { c.op(Op.F2D, 1); c.static("java/lang/Double", "doubleToRawLongBits", "(D)J") }
                        'D' -> c.static("java/lang/Double", "doubleToRawLongBits", "(D)J")
                    }
                    c.op(Op.LASTORE, -4)
                }
            }
            c.static(KXRT, cn, desc(FuncT(f.ret, f.params, false), va))
            result(i)
        }

        /** Greedy 8/4/2/1-byte chunks (offset, size) covering [n] bytes. */
        fun chunks(n: Long): List<Pair<Long, Int>> {
            val r = ArrayList<Pair<Long, Int>>(); var off = 0L
            for (sz in intArrayOf(8, 4, 2, 1)) while (n - off >= sz) { r.add(off to sz); off += sz }
            return r
        }
        fun uaddr(p: TV, off: Long) { c.field(Op.GETSTATIC, KXRT, "U", UNSAFE_D); addr(p, off) }
        fun memDesc(sz: Int) = when (sz) { 8 -> "Long" to 'J'; 4 -> "Int" to 'I'; 2 -> "Short" to 'S'; else -> "Byte" to 'B' }
        /** Small constant-size memcpy/memmove: every chunk is loaded into a local before the first store (overlap-safe),
         *  so no Unsafe.copyMemory call (a VM call on every use). */
        fun inlineCopy(dst: TV, src: TV, n: Long) {
            val cs = chunks(n)
            val slots = cs.map { (off, sz) ->
                val (nm, d) = memDesc(sz)
                uaddr(src, off); c.invoke(Op.INVOKEVIRTUAL, UNSAFE, "get$nm", "(J)$d")
                val k = if (sz == 8) 'J' else 'I'; val s = newSlot(k); c.store(k, s); s
            }
            for ((idx, ch) in cs.withIndex()) {
                val (nm, d) = memDesc(ch.second)
                uaddr(dst, ch.first); c.load(if (ch.second == 8) 'J' else 'I', slots[idx]); c.invoke(Op.INVOKEVIRTUAL, UNSAFE, "put$nm", "(J$d)V")
            }
        }
        /** Small constant memset: stores of the repeated byte. */
        fun inlineSet(dst: TV, v: Long, n: Long) {
            val pat = (v and 0xFF) * 0x0101010101010101L
            for ((off, sz) in chunks(n)) {
                val (nm, d) = memDesc(sz)
                uaddr(dst, off)
                if (sz == 8) c.lconst(pat) else c.iconst(when (sz) { 4 -> pat.toInt(); 2 -> pat.toInt().toShort().toInt(); else -> pat.toInt().toByte().toInt() })
                c.invoke(Op.INVOKEVIRTUAL, UNSAFE, "put$nm", "(J$d)V")
            }
        }

        fun math(name: String, t: LType, nargs: Int, owner: String = "java/lang/Math") {
            val f = kind(t) == 'F'
            for (k in 0 until nargs) { push(curCall!!.ops[k]); if (f) c.op(Op.F2D, 1) }
            c.static(owner, name, "(" + "D".repeat(nargs) + ")D")
            if (f) c.op(Op.D2F, -1)
        }
        var curCall: Inst? = null

        fun intrinsic(i: Inst, full: String) {
            curCall = i
            val base = full.removePrefix("llvm.").split('.').let { parts ->
                // drop type suffixes (.i64, .f64, .p0, .p0.p0.i64, .v4i32 ...)
                parts.takeWhile { p -> !(p.matches(Regex("[ifpv][0-9].*")) || p == "f32" || p == "f64") }.joinToString(".")
            }
            val t = i.ty
            val ops = i.ops
            when (base) {
                "lifetime.start", "lifetime.end", "assume", "dbg.value", "dbg.declare", "dbg.label", "dbg.assign",
                "experimental.noalias.scope.decl", "invariant.end", "sideeffect", "donothing", "pseudoprobe", "var.annotation",
                "prefetch", "codeview.annotation" -> return
                "invariant.start" -> { c.lconst(0) }
                "expect", "expect.with.probability", "ptr.annotation", "launder.invariant.group", "strip.invariant.group",
                "threadlocal.address", "ssa.copy", "annotation" -> push(ops[0])
                "objectsize" -> { val min = (ops[1].v as? CInt)?.v ?: 0; pushInt(t, if (min != 0L) 0 else -1) }
                "memcpy", "memmove", "memcpy.inline" -> {
                    val n = (ops[2].v as? CInt)?.v
                    if (n != null && n in 0..INLINE_MEM) { inlineCopy(ops[0], ops[1], n); return }
                    push(ops[0]); push(ops[1]); push(ops[2]); toLong(ops[2].t)
                    c.static(KXRT, if (base == "memmove") "memmove" else "memcpy", "(JJJ)J"); c.op(Op.POP2, -2); return
                }
                "memset", "memset.inline" -> {
                    val n = (ops[2].v as? CInt)?.v; val v = (ops[1].v as? CInt)?.v
                    if (n != null && v != null && n in 0..INLINE_MEM) { inlineSet(ops[0], v, n); return }
                    push(ops[0]); push(ops[1]); push(ops[2]); toLong(ops[2].t)
                    c.static(KXRT, "memset", "(JIJ)J"); c.op(Op.POP2, -2); return
                }
                "smax", "smin" -> {
                    if (kind(t) == 'I') { push(ops[0]); push(ops[1]); c.static("java/lang/Math", base.substring(1), "(II)I") }
                    else { push(ops[0]); push(ops[1]); c.static("java/lang/Math", base.substring(1), "(JJ)J") }
                }
                "umax", "umin" -> {
                    val k = kind(t)
                    if (bits(t) == 32 || bits(t) == 64) { push(ops[0]); push(ops[1]); c.static(KXRT, base + if (k == 'I') "32" else "64", if (k == 'I') "(II)I" else "(JJ)J") }
                    else { push(ops[0]); zext(t); push(ops[1]); zext(t); c.static("java/lang/Math", base.substring(1), if (k == 'I') "(II)I" else "(JJ)J"); norm(t) }
                }
                "abs" -> { push(ops[0]); c.static("java/lang/Math", "abs", if (kind(t) == 'I') "(I)I" else "(J)J"); norm(t) }
                "scmp", "ucmp" -> {
                    val at = ops[0].t; val k = kind(at)
                    push(ops[0]); if (base == "ucmp" && bits(at) < 32) zext(at)
                    push(ops[1]); if (base == "ucmp" && bits(at) < 32) zext(at)
                    if (base == "ucmp" && (bits(at) == 32 || bits(at) == 64)) c.static(if (k == 'I') "java/lang/Integer" else "java/lang/Long", "compareUnsigned", if (k == 'I') "(II)I" else "(JJ)I")
                    else c.static(if (k == 'I') "java/lang/Integer" else "java/lang/Long", "compare", if (k == 'I') "(II)I" else "(JJ)I")
                    c.static("java/lang/Integer", "signum", "(I)I")
                    if (kind(t) == 'J') c.op(Op.I2L, 1)
                }
                "sqrt" -> math("sqrt", t, 1)
                "fabs" -> { push(ops[0]); c.static("java/lang/Math", "abs", if (kind(t) == 'F') "(F)F" else "(D)D") }
                "floor", "ceil", "rint", "exp", "log", "log10", "sin", "cos", "tan", "pow", "atan2" ->
                    math(base, t, if (base == "pow" || base == "atan2") 2 else 1)
                "nearbyint", "roundeven" -> math("rint", t, 1)
                "trunc" -> math("trunc", t, 1, KXRT)
                "round" -> math("round", t, 1, KXRT)
                "exp2" -> math("exp2", t, 1, KXRT)
                "log2" -> math("log2", t, 1, KXRT)
                "copysign" -> { push(ops[0]); push(ops[1]); c.static("java/lang/Math", "copySign", if (kind(t) == 'F') "(FF)F" else "(DD)D") }
                "minnum", "maxnum", "minimum", "maximum", "minimumnum", "maximumnum" -> {
                    push(ops[0]); push(ops[1])
                    val n = if (base.startsWith("min")) "min" else "max"
                    if (base.endsWith("num")) c.static(KXRT, n + "num", if (kind(t) == 'F') "(FF)F" else "(DD)D")
                    else c.static("java/lang/Math", n, if (kind(t) == 'F') "(FF)F" else "(DD)D")
                }
                "fma", "fmuladd" -> { push(ops[0]); push(ops[1]); push(ops[2]); c.static("java/lang/Math", "fma", if (kind(t) == 'F') "(FFF)F" else "(DDD)D") }
                "powi" -> { push(ops[0]); if (kind(t) == 'F') c.op(Op.F2D, 1); push(ops[1]); c.op(Op.I2D, 1); c.static("java/lang/Math", "pow", "(DD)D"); if (kind(t) == 'F') c.op(Op.D2F, -1) }
                "ctlz", "cttz", "ctpop" -> {
                    val n = bits(t); val k = kind(t)
                    push(ops[0])
                    val fn = when (base) { "ctlz" -> "numberOfLeadingZeros"; "cttz" -> "numberOfTrailingZeros"; else -> "bitCount" }
                    if (k == 'I') {
                        if (n < 32) { if (base == "cttz") { c.iconst(1 shl n); c.op(Op.IOR, -1) } else zext(t) }
                        c.static("java/lang/Integer", fn, "(I)I")
                        if (base == "ctlz" && n < 32) { c.iconst(32 - n); c.op(Op.ISUB, -1) }
                    } else {
                        if (n < 64) { if (base == "cttz") { c.lconst(1L shl n); c.op(Op.LOR, -2) } else zext(t) }
                        c.static("java/lang/Long", fn, "(J)I")
                        if (base == "ctlz" && n < 64) { c.iconst(64 - n); c.op(Op.ISUB, -1) }
                        c.op(Op.I2L, 1)
                    }
                }
                "bswap" -> {
                    push(ops[0])
                    when (bits(t)) {
                        16 -> { c.static("java/lang/Integer", "reverseBytes", "(I)I"); c.iconst(16); c.op(Op.ISHR, -1) }
                        32 -> c.static("java/lang/Integer", "reverseBytes", "(I)I")
                        64 -> c.static("java/lang/Long", "reverseBytes", "(J)J")
                        else -> err("bswap.i${bits(t)}")
                    }
                }
                "fshl", "fshr" -> {
                    val n = bits(t); if (n != 32 && n != 64) err("$base.i$n")
                    push(ops[0]); push(ops[1]); push(ops[2]); c.static(KXRT, base + n, if (n == 32) "(III)I" else "(JJJ)J")
                }
                "fptosi.sat" -> { push(ops[0]); conv(kind(ops[0].t), kind(t)); if (bits(t) < 32) err("fptosi.sat.i${bits(t)}") }
                "fptoui.sat" -> { push(ops[0]); if (kind(ops[0].t) == 'F') c.op(Op.F2D, 1); c.static(KXRT, "fptouisat" + bits(t), "(D)" + kind(t)) }
                "sadd.with.overflow", "uadd.with.overflow", "ssub.with.overflow", "usub.with.overflow", "smul.with.overflow", "umul.with.overflow" -> {
                    val at = ops[0].t; val n = bits(at); val sl = aggSlots[i.res] ?: return
                    val k = kind(at)
                    val opn = base.substring(1, 4) // add, sub, mul
                    // value
                    intOp(opn, at, ops[0], ops[1]); c.store(k, sl[0])
                    // overflow flag via KxRt.<s|u><op>ovf<32|64>(a, b) on values extended to 32/64 bits
                    if (n != 32 && n != 64) err("$base.i$n is not supported on the jvm target")
                    push(ops[0]); push(ops[1])
                    c.static(KXRT, "${base[0]}${opn}ovf$n", if (n == 32) "(II)I" else "(JJ)I")
                    c.store('I', sl[1])
                    return
                }
                "stacksave" -> c.field(Op.GETSTATIC, KXRT, "SP", "J")
                "stackrestore" -> { push(ops[0]); c.field(Op.PUTSTATIC, KXRT, "SP", "J"); return }
                "frameaddress" -> c.field(Op.GETSTATIC, KXRT, "SP", "J")
                "returnaddress" -> c.lconst(0)
                "trap", "debugtrap", "ubsantrap" -> { c.static(KXRT, "trap", "()Ljava/lang/RuntimeException;"); c.op(Op.ATHROW, -1); return }
                else -> err("intrinsic @$full is not supported on the jvm target")
            }
            result(i)
        }
    }

    // ------------------------------------------------------------ dispatchers and wrappers

    /** icall$N(long fn, args...): calls the function with id fn (all functions of this descriptor are candidates). */
    private fun dispatcher(d: String, name: String) {
        val c = Code(cp, true)
        val params = d.substring(1, d.indexOf(')'))
        var slotN = 2
        val slots = params.map { k -> val s = slotN; slotN += if (k == 'J' || k == 'D') 2 else 1; s }
        c.maxLocals = slotN
        c.load('J', 0); c.op(Op.L2I, -1)
        val ret = d.substringAfter(')')
        // C allows calling through a pointer type with extra arguments (they are dropped, e.g. atexit handlers called
        // as void(void*)) and ignoring the result: the callee's parameters must be a prefix of the call's.
        fun fits(f: LFunc): Boolean {
            if (f.varargs) return false
            val fd = desc(FuncT(f.ret, f.params, false))
            val fp = fd.substring(1, fd.indexOf(')')); val fr = fd.substringAfter(')')
            return params.startsWith(fp) && (fr == ret || ret == "V")
        }
        val cands = fnId.entries.filter { (n, _) -> m.funcs[n]?.let(::fits) == true }
        val dflt = Label()
        if (cands.isEmpty()) { c.op(Op.POP, -1); c.jump(Op.GOTO, dflt) }
        else {
            val labels = cands.map { Label() }
            c.lookupswitch(cands.map { it.value }.toIntArray(), labels, dflt)
            for ((k, e) in cands.withIndex()) {
                c.place(labels[k]); c.stack = 0
                val f = m.funcs[e.key]!!
                params.take(f.params.size).forEachIndexed { pi, ch -> c.load(ch, slots[pi]) }
                val fd = desc(FuncT(f.ret, f.params, false))
                if (f.isDecl) c.static(className, extWrappers.getOrPut(e.key) { "ext$" + cName(e.key) }, fd)
                else c.static(className, jname[e.key]!!, fd)
                when (fd.substringAfter(')')) { ret, "V" -> {}; "J", "D" -> c.op(Op.POP2, -2); else -> c.op(Op.POP, -1) }
                val r = ret
                c.op(when (r) { "V" -> Op.RETURN; "I" -> Op.IRETURN; "J" -> Op.LRETURN; "F" -> Op.FRETURN; else -> Op.DRETURN }, 0)
            }
        }
        c.place(dflt); c.stack = 0
        c.load('J', 0); c.static(KXRT, "badCall", "(J)Ljava/lang/RuntimeException;"); c.op(Op.ATHROW, -1)
        cb.method(ACC_PUBLIC or ACC_STATIC, name, "(J" + d.substring(1), c)
    }

    /** Static wrapper for an external (KxRt) function whose address is taken. */
    private fun wrapper(ext: String, name: String) {
        val f = m.funcs[ext]!!
        val cn = cName(ext)
        if (cn !in runtimeFuncs) err("external function $cn is not available on the jvm target")
        if (f.varargs) err("address of variadic function $cn")
        val d = desc(f.type)
        val c = Code(cp, false)
        var s = 0
        for (p in f.params) { c.load(kind(p), s); s += if (kind(p) == 'J' || kind(p) == 'D') 2 else 1 }
        c.maxLocals = s
        c.static(KXRT, cn, d)
        c.op(when (d.substringAfter(')')) { "V" -> Op.RETURN; "I" -> Op.IRETURN; "J" -> Op.LRETURN; "F" -> Op.FRETURN; else -> Op.DRETURN }, 0)
        cb.method(ACC_PUBLIC or ACC_STATIC, name, d, c)
    }
}
