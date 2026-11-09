package newkotlin.jvm

import newkotlin.CompileError

/*
 * Minimal JVM class file writer (no dependencies). Classes are written as version 49 (Java 5): the JVM verifies
 * them by type inference, so no StackMapTable frames are needed, and the JIT treats them like any other class.
 */

class ByteBuf(cap: Int = 256) {
    var a = ByteArray(cap); var n = 0
    private fun room(k: Int) { if (n + k > a.size) a = a.copyOf(maxOf(a.size * 2, n + k)) }
    fun u1(v: Int) { room(1); a[n++] = v.toByte() }
    fun u2(v: Int) { room(2); a[n++] = (v shr 8).toByte(); a[n++] = v.toByte() }
    fun u4(v: Int) { room(4); a[n++] = (v shr 24).toByte(); a[n++] = (v shr 16).toByte(); a[n++] = (v shr 8).toByte(); a[n++] = v.toByte() }
    fun u8(v: Long) { u4((v shr 32).toInt()); u4(v.toInt()) }
    fun bytes(b: ByteArray, off: Int = 0, len: Int = b.size) { room(len); b.copyInto(a, n, off, off + len); n += len }
    fun put2(at: Int, v: Int) { a[at] = (v shr 8).toByte(); a[at + 1] = v.toByte() }
    fun put4(at: Int, v: Int) { a[at] = (v shr 24).toByte(); a[at + 1] = (v shr 16).toByte(); a[at + 2] = (v shr 8).toByte(); a[at + 3] = v.toByte() }
    fun toBytes() = a.copyOf(n)
}

/** Modified UTF-8 length of [s] (as stored in a CONSTANT_Utf8). */
fun mutf8Len(s: String): Int { var k = 0; for (c in s) k += if (c.code in 1..0x7F) 1 else if (c.code <= 0x7FF) 2 else 3; return k }

class ConstPool {
    val out = ByteBuf(1024)
    var count = 1
    private val map = HashMap<String, Int>()
    private fun add(key: String, slots: Int, write: () -> Unit): Int = map.getOrPut(key) {
        val idx = count; write(); count += slots
        if (count > 0xFFFF) throw CompileError("jvm: constant pool overflow (program too large for one class)")
        idx
    }
    fun utf8(s: String): Int = add("U$s", 1) {
        out.u1(1); out.u2(mutf8Len(s))
        for (c in s) {
            val v = c.code
            when { v in 1..0x7F -> out.u1(v); v <= 0x7FF -> { out.u1(0xC0 or (v shr 6)); out.u1(0x80 or (v and 0x3F)) }
                else -> { out.u1(0xE0 or (v shr 12)); out.u1(0x80 or ((v shr 6) and 0x3F)); out.u1(0x80 or (v and 0x3F)) } }
        }
    }
    fun cls(name: String): Int { val u = utf8(name); return add("C$name", 1) { out.u1(7); out.u2(u) } }
    fun str(s: String): Int { val u = utf8(s); return add("S$s", 1) { out.u1(8); out.u2(u) } }
    fun int(v: Int): Int = add("I$v", 1) { out.u1(3); out.u4(v) }
    fun float(v: Float): Int = add("F${v.toRawBits()}", 1) { out.u1(4); out.u4(v.toRawBits()) }
    fun long(v: Long): Int = add("J$v", 2) { out.u1(5); out.u8(v) }
    fun double(v: Double): Int = add("D${v.toRawBits()}", 2) { out.u1(6); out.u8(v.toRawBits()) }
    fun nat(name: String, desc: String): Int { val a = utf8(name); val b = utf8(desc); return add("N$name:$desc", 1) { out.u1(12); out.u2(a); out.u2(b) } }
    private fun ref(tag: Int, owner: String, name: String, desc: String): Int {
        val c = cls(owner); val nt = nat(name, desc)
        return add("R$tag$owner.$name:$desc", 1) { out.u1(tag); out.u2(c); out.u2(nt) }
    }
    fun field(owner: String, name: String, desc: String) = ref(9, owner, name, desc)
    fun method(owner: String, name: String, desc: String) = ref(10, owner, name, desc)
    fun imethod(owner: String, name: String, desc: String) = ref(11, owner, name, desc)
}

class Label { var pos = -1 }

/** Thrown when a 16-bit branch offset overflows; the method is then re-emitted with wide (goto_w) branches. */
class BranchTooFar : Exception()

/** Argument and return slot counts of a method descriptor. */
fun descSlots(desc: String): Pair<Int, Int> {
    var i = 1; var args = 0
    while (desc[i] != ')') {
        when (desc[i]) {
            'J', 'D' -> { args += 2; i++ }
            'L' -> { args++; i = desc.indexOf(';', i) + 1 }
            '[' -> { while (desc[i] == '[') i++; if (desc[i] == 'L') i = desc.indexOf(';', i) + 1 else i++; args++ }
            else -> { args++; i++ }
        }
    }
    val r = when (desc[i + 1]) { 'V' -> 0; 'J', 'D' -> 2; else -> 1 }
    return args to r
}

object Op {
    const val ACONST_NULL = 0x01; const val ICONST_0 = 0x03; const val LCONST_0 = 0x09; const val FCONST_0 = 0x0b; const val DCONST_0 = 0x0e
    const val BIPUSH = 0x10; const val SIPUSH = 0x11; const val LDC = 0x12; const val LDC_W = 0x13; const val LDC2_W = 0x14
    const val ILOAD = 0x15; const val LLOAD = 0x16; const val FLOAD = 0x17; const val DLOAD = 0x18; const val ALOAD = 0x19
    const val ISTORE = 0x36; const val LSTORE = 0x37; const val FSTORE = 0x38; const val DSTORE = 0x39; const val ASTORE = 0x3a
    const val LASTORE = 0x50
    const val POP = 0x57; const val POP2 = 0x58; const val DUP = 0x59; const val DUP_X1 = 0x5a; const val DUP2 = 0x5c; const val SWAP = 0x5f
    const val IADD = 0x60; const val LADD = 0x61; const val FADD = 0x62; const val DADD = 0x63
    const val ISUB = 0x64; const val LSUB = 0x65; const val FSUB = 0x66; const val DSUB = 0x67
    const val IMUL = 0x68; const val LMUL = 0x69; const val FMUL = 0x6a; const val DMUL = 0x6b
    const val IDIV = 0x6c; const val LDIV = 0x6d; const val FDIV = 0x6e; const val DDIV = 0x6f
    const val IREM = 0x70; const val LREM = 0x71; const val FREM = 0x72; const val DREM = 0x73
    const val INEG = 0x74; const val LNEG = 0x75; const val FNEG = 0x76; const val DNEG = 0x77
    const val ISHL = 0x78; const val LSHL = 0x79; const val ISHR = 0x7a; const val LSHR = 0x7b; const val IUSHR = 0x7c; const val LUSHR = 0x7d
    const val IAND = 0x7e; const val LAND = 0x7f; const val IOR = 0x80; const val LOR = 0x81; const val IXOR = 0x82; const val LXOR = 0x83
    const val I2L = 0x85; const val I2F = 0x86; const val I2D = 0x87; const val L2I = 0x88; const val L2F = 0x89; const val L2D = 0x8a
    const val F2I = 0x8b; const val F2L = 0x8c; const val F2D = 0x8d; const val D2I = 0x8e; const val D2L = 0x8f; const val D2F = 0x90
    const val I2B = 0x91; const val I2C = 0x92; const val I2S = 0x93
    const val LCMP = 0x94; const val FCMPL = 0x95; const val FCMPG = 0x96; const val DCMPL = 0x97; const val DCMPG = 0x98
    const val IFEQ = 0x99; const val IFNE = 0x9a; const val IFLT = 0x9b; const val IFGE = 0x9c; const val IFGT = 0x9d; const val IFLE = 0x9e
    const val IF_ICMPEQ = 0x9f; const val IF_ICMPNE = 0xa0; const val IF_ICMPLT = 0xa1; const val IF_ICMPGE = 0xa2; const val IF_ICMPGT = 0xa3; const val IF_ICMPLE = 0xa4
    const val GOTO = 0xa7; const val TABLESWITCH = 0xaa; const val LOOKUPSWITCH = 0xab
    const val IRETURN = 0xac; const val LRETURN = 0xad; const val FRETURN = 0xae; const val DRETURN = 0xaf; const val ARETURN = 0xb0; const val RETURN = 0xb1
    const val GETSTATIC = 0xb2; const val PUTSTATIC = 0xb3; const val INVOKEVIRTUAL = 0xb6; const val INVOKESPECIAL = 0xb7
    const val INVOKESTATIC = 0xb8; const val INVOKEINTERFACE = 0xb9; const val NEW = 0xbb; const val NEWARRAY = 0xbc
    const val ATHROW = 0xbf; const val WIDE = 0xc4; const val GOTO_W = 0xc8
}

/** Bytecode of one method. Stack depth is tracked linearly (an over-estimate across branches is harmless). */
class Code(val cp: ConstPool, val wide: Boolean) {
    val b = ByteBuf(1024)
    var stack = 0; var maxStack = 0; var maxLocals = 0
    private class Fix(val at: Int, val patch: Int, val l: Label, val four: Boolean)
    private val fixes = ArrayList<Fix>()

    fun adj(d: Int) { stack += d; if (stack > maxStack) maxStack = stack }
    fun op(o: Int, delta: Int) { b.u1(o); adj(delta) }

    fun iconst(v: Int) {
        when (v) {
            in -1..5 -> op(Op.ICONST_0 + v, 1)
            in -128..127 -> { op(Op.BIPUSH, 1); b.u1(v) }
            in -32768..32767 -> { op(Op.SIPUSH, 1); b.u2(v) }
            else -> ldc(cp.int(v))
        }
    }
    fun lconst(v: Long) { if (v == 0L || v == 1L) op(Op.LCONST_0 + v.toInt(), 2) else { op(Op.LDC2_W, 2); b.u2(cp.long(v)) } }
    fun fconst(v: Float) { if (v.toRawBits() == 0 || v == 1f || v == 2f) op(Op.FCONST_0 + v.toInt(), 1) else ldc(cp.float(v)) }
    fun dconst(v: Double) { if (v.toRawBits() == 0L || v == 1.0) op(Op.DCONST_0 + v.toInt(), 2) else { op(Op.LDC2_W, 2); b.u2(cp.double(v)) } }
    fun sconst(s: String) = ldc(cp.str(s))
    private fun ldc(idx: Int) { if (idx < 256) { op(Op.LDC, 1); b.u1(idx) } else { op(Op.LDC_W, 1); b.u2(idx) } }

    /** kind: 'I', 'J', 'F', 'D', 'A' */
    fun load(kind: Char, slot: Int) = local(when (kind) { 'I' -> Op.ILOAD; 'J' -> Op.LLOAD; 'F' -> Op.FLOAD; 'D' -> Op.DLOAD; else -> Op.ALOAD }, slot, if (kind == 'J' || kind == 'D') 2 else 1)
    fun store(kind: Char, slot: Int) = local(when (kind) { 'I' -> Op.ISTORE; 'J' -> Op.LSTORE; 'F' -> Op.FSTORE; 'D' -> Op.DSTORE; else -> Op.ASTORE }, slot, if (kind == 'J' || kind == 'D') -2 else -1)
    private fun local(o: Int, slot: Int, delta: Int) {
        if (slot < 256) { op(o, delta); b.u1(slot) } else { b.u1(Op.WIDE); op(o, delta); b.u2(slot) }
    }

    fun invoke(o: Int, owner: String, name: String, desc: String) {
        val (a, r) = descSlots(desc)
        val self = if (o == Op.INVOKESTATIC) 0 else 1
        if (o == Op.INVOKEINTERFACE) { op(o, r - a - self); b.u2(cp.imethod(owner, name, desc)); b.u1(a + 1); b.u1(0) }
        else { op(o, r - a - self); b.u2(cp.method(owner, name, desc)) }
    }
    fun static(owner: String, name: String, desc: String) = invoke(Op.INVOKESTATIC, owner, name, desc)
    fun field(o: Int, owner: String, name: String, desc: String) {
        val s = if (desc == "J" || desc == "D") 2 else 1
        op(o, when (o) { Op.GETSTATIC -> s; Op.PUTSTATIC -> -s; else -> 0 }); b.u2(cp.field(owner, name, desc))
    }
    fun new(cls: String) { op(Op.NEW, 1); b.u2(cp.cls(cls)) }

    fun place(l: Label) { l.pos = b.n }

    /** goto or a conditional branch ([delta] = its stack effect). */
    fun jump(o: Int, l: Label, delta: Int = 0) {
        if (!wide) { val at = b.n; op(o, delta); fixes.add(Fix(at, b.n, l, false)); b.u2(0); return }
        if (o == Op.GOTO) { val at = b.n; op(Op.GOTO_W, 0); fixes.add(Fix(at, b.n, l, true)); b.u4(0); return }
        val inv = if (o % 2 == 1) o + 1 else o - 1  // ifeq<->ifne ... if_icmpgt<->if_icmple
        op(inv, delta); b.u2(8)
        val at = b.n; b.u1(Op.GOTO_W); fixes.add(Fix(at, b.n, l, true)); b.u4(0)
    }

    fun tableswitch(lo: Int, targets: List<Label>, dflt: Label) {
        val at = b.n; op(Op.TABLESWITCH, -1)
        while (b.n % 4 != 0) b.u1(0)
        fixes.add(Fix(at, b.n, dflt, true)); b.u4(0)
        b.u4(lo); b.u4(lo + targets.size - 1)
        for (l in targets) { fixes.add(Fix(at, b.n, l, true)); b.u4(0) }
    }
    fun lookupswitch(keys: IntArray, targets: List<Label>, dflt: Label) {
        val at = b.n; op(Op.LOOKUPSWITCH, -1)
        while (b.n % 4 != 0) b.u1(0)
        fixes.add(Fix(at, b.n, dflt, true)); b.u4(0)
        b.u4(keys.size)
        val order = keys.indices.sortedBy { keys[it] }
        for (k in order) { b.u4(keys[k]); fixes.add(Fix(at, b.n, targets[k], true)); b.u4(0) }
    }

    fun finish(): ByteArray {
        for (f in fixes) {
            if (f.l.pos < 0) throw IllegalStateException("unplaced label")
            val off = f.l.pos - f.at
            if (f.four) b.put4(f.patch, off)
            else { if (off < -32768 || off > 32767) throw BranchTooFar(); b.put2(f.patch, off) }
        }
        if (b.n > 65535) throw CompileError("jvm: a function is too large for one JVM method (${b.n} bytes of bytecode)")
        return b.toBytes()
    }
}

class ClassBuilder(val name: String, val superName: String = "java/lang/Object", val interfaces: List<String> = emptyList()) {
    val cp = ConstPool()
    private class M(val acc: Int, val name: String, val desc: String, val code: ByteArray, val maxStack: Int, val maxLocals: Int)
    private class F(val acc: Int, val name: String, val desc: String)
    private val methods = ArrayList<M>()
    private val fields = ArrayList<F>()

    fun field(acc: Int, name: String, desc: String) { fields.add(F(acc, name, desc)) }
    fun method(acc: Int, name: String, desc: String, c: Code) {
        val bytes = c.finish()
        methods.add(M(acc, name, desc, bytes, c.maxStack, c.maxLocals))
    }

    fun bytes(): ByteArray {
        val thisIdx = cp.cls(name); val superIdx = cp.cls(superName); val ifs = interfaces.map { cp.cls(it) }
        val codeIdx = cp.utf8("Code")
        val fs = fields.map { Triple(it.acc, cp.utf8(it.name), cp.utf8(it.desc)) }
        val ms = methods.map { Triple(it, cp.utf8(it.name), cp.utf8(it.desc)) }
        val o = ByteBuf(4096 + cp.out.n + methods.sumOf { it.code.size + 32 })
        o.u4(0xCAFEBABE.toInt()); o.u2(0); o.u2(49)
        o.u2(cp.count); o.bytes(cp.out.a, 0, cp.out.n)
        o.u2(0x0001 or 0x0020) // public super
        o.u2(thisIdx); o.u2(superIdx)
        o.u2(ifs.size); ifs.forEach { o.u2(it) }
        o.u2(fs.size); for ((acc, n, d) in fs) { o.u2(acc); o.u2(n); o.u2(d); o.u2(0) }
        o.u2(ms.size)
        for ((m, n, d) in ms) {
            o.u2(m.acc); o.u2(n); o.u2(d); o.u2(1)
            o.u2(codeIdx); o.u4(12 + m.code.size)
            o.u2(m.maxStack); o.u2(m.maxLocals); o.u4(m.code.size); o.bytes(m.code)
            o.u2(0); o.u2(0) // exception table, attributes
        }
        o.u2(0)
        return o.toBytes()
    }
}

const val ACC_PUBLIC = 0x0001; const val ACC_PRIVATE = 0x0002; const val ACC_STATIC = 0x0008; const val ACC_FINAL = 0x0010
