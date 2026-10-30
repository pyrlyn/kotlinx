package newkotlin

/**
 * Library code written in KotlinX compiler itself, merged into a program that uses it: ranges and progressions as values
 * (`IntRange`, `IntProgression`, `LongRange`, `LongProgression`, `CharRange`, `CharProgression`).
 * Range operators outside a `for` header call the `kt_*` functions; `for` over a progression value iterates its fields.
 */
private val INT_PROGRESSION = """
fun kt_modI(a: Int, b: Int): Int { val m = a % b; return if (m >= 0) m else m + b }
fun kt_lastI(start: Int, end: Int, step: Int): Int {
    if (step > 0) {
        if (start >= end) return end
        return end - kt_modI(kt_modI(end, step) - kt_modI(start, step), step)
    }
    if (start <= end) return end
    return end + kt_modI(kt_modI(start, 0 - step) - kt_modI(end, 0 - step), 0 - step)
}
open class IntProgression(val first: Int, val last: Int, val step: Int) {
    fun isEmpty(): Boolean = if (step > 0) first > last else first < last
    fun contains(x: Int): Boolean {
        if (isEmpty()) return false
        if (step > 0) { if (x < first || x > last) return false } else { if (x > first || x < last) return false }
        return (x.toLong() - first.toLong()) % step.toLong() == 0L
    }
    override fun toString(): String = if (step > 0) "" + first + ".." + last + " step " + step else "" + first + " downTo " + last + " step " + (0 - step)
    fun reversed(): IntProgression = IntProgression(last, first, 0 - step)
    fun count(): Int {
        if (isEmpty()) return 0
        if (step > 0) return ((last.toLong() - first.toLong()) / step.toLong() + 1L).toInt()
        return ((first.toLong() - last.toLong()) / (0L - step.toLong()) + 1L).toInt()
    }
    fun toList(): List<Int> {
        val r = ArrayList<Int>()
        for (x in this) r.add(x)
        return r
    }
}
class IntRange(start: Int, endInclusive: Int) : IntProgression(start, endInclusive, 1) {
    val start: Int = start
    val endInclusive: Int = endInclusive
    override fun toString(): String = "" + first + ".." + last
}
fun kt_rangeToI(a: Int, b: Int): IntRange = IntRange(a, b)
fun kt_untilI(a: Int, b: Int): IntRange = if (b == -2147483647 - 1) IntRange(1, 0) else IntRange(a, b - 1)
fun kt_downToI(a: Int, b: Int): IntProgression = IntProgression(a, b, -1)
fun kt_stepI(p: IntProgression, n: Int): IntProgression {
    if (n <= 0) throw IllegalArgumentException("Step must be positive, was: " + n + ".")
    val s = if (p.step > 0) n else 0 - n
    return IntProgression(p.first, kt_lastI(p.first, p.last, s), s)
}
"""

private val LONG_PROGRESSION = """
fun kt_modL(a: Long, b: Long): Long { val m = a % b; return if (m >= 0L) m else m + b }
fun kt_lastL(start: Long, end: Long, step: Long): Long {
    if (step > 0L) {
        if (start >= end) return end
        return end - kt_modL(kt_modL(end, step) - kt_modL(start, step), step)
    }
    if (start <= end) return end
    return end + kt_modL(kt_modL(start, 0L - step) - kt_modL(end, 0L - step), 0L - step)
}
open class LongProgression(val first: Long, val last: Long, val step: Long) {
    fun isEmpty(): Boolean = if (step > 0L) first > last else first < last
    fun contains(x: Long): Boolean {
        if (isEmpty()) return false
        if (step > 0L) { if (x < first || x > last) return false } else { if (x > first || x < last) return false }
        return (x - first) % step == 0L
    }
    override fun toString(): String = if (step > 0L) "" + first + ".." + last + " step " + step else "" + first + " downTo " + last + " step " + (0L - step)
    fun reversed(): LongProgression = LongProgression(last, first, 0L - step)
    fun count(): Int {
        if (isEmpty()) return 0
        if (step > 0L) return ((last - first) / step + 1L).toInt()
        return ((first - last) / (0L - step) + 1L).toInt()
    }
    fun toList(): List<Long> {
        val r = ArrayList<Long>()
        for (x in this) r.add(x)
        return r
    }
}
class LongRange(start: Long, endInclusive: Long) : LongProgression(start, endInclusive, 1L) {
    val start: Long = start
    val endInclusive: Long = endInclusive
    override fun toString(): String = "" + first + ".." + last
}
fun kt_rangeToL(a: Long, b: Long): LongRange = LongRange(a, b)
fun kt_untilL(a: Long, b: Long): LongRange = if (b == -9223372036854775807L - 1L) LongRange(1L, 0L) else LongRange(a, b - 1L)
fun kt_downToL(a: Long, b: Long): LongProgression = LongProgression(a, b, -1L)
fun kt_stepL(p: LongProgression, n: Long): LongProgression {
    if (n <= 0L) throw IllegalArgumentException("Step must be positive, was: " + n + ".")
    val s = if (p.step > 0L) n else 0L - n
    return LongProgression(p.first, kt_lastL(p.first, p.last, s), s)
}
"""

private val CHAR_PROGRESSION = """
open class CharProgression(val first: Char, val last: Char, val step: Int) {
    fun isEmpty(): Boolean = if (step > 0) first > last else first < last
    fun contains(x: Char): Boolean {
        if (isEmpty()) return false
        if (step > 0) { if (x < first || x > last) return false } else { if (x > first || x < last) return false }
        return (x - first) % step == 0
    }
    override fun toString(): String = if (step > 0) "" + first + ".." + last + " step " + step else "" + first + " downTo " + last + " step " + (0 - step)
    fun reversed(): CharProgression = CharProgression(last, first, 0 - step)
    fun count(): Int {
        if (isEmpty()) return 0
        if (step > 0) return (last - first) / step + 1
        return (first - last) / (0 - step) + 1
    }
    fun toList(): List<Char> {
        val r = ArrayList<Char>()
        for (x in this) r.add(x)
        return r
    }
}
class CharRange(start: Char, endInclusive: Char) : CharProgression(start, endInclusive, 1) {
    val start: Char = start
    val endInclusive: Char = endInclusive
    override fun toString(): String = "" + first + ".." + last
}
fun kt_rangeToC(a: Char, b: Char): CharRange = CharRange(a, b)
fun kt_untilC(a: Char, b: Char): CharRange = if (b.code == 0) CharRange(1.toChar(), 0.toChar()) else CharRange(a, b - 1)
fun kt_downToC(a: Char, b: Char): CharProgression = CharProgression(a, b, -1)
fun kt_stepC(p: CharProgression, n: Int): CharProgression {
    if (n <= 0) throw IllegalArgumentException("Step must be positive, was: " + n + ".")
    val s = if (p.step > 0) n else 0 - n
    return CharProgression(p.first, kt_lastI(p.first.code, p.last.code, s).toChar(), s)
}
"""

private val UNSIGNED_PROGRESSION = """
fun kt_diffU(a: UInt, b: UInt, c: UInt): UInt { val ac = a % c; val bc = b % c; return if (ac >= bc) ac - bc else ac - bc + c }
fun kt_lastU(start: UInt, end: UInt, step: Int): UInt {
    if (step > 0) { if (start >= end) return end; return end - kt_diffU(end, start, step.toUInt()) }
    if (start <= end) return end
    return end + kt_diffU(start, end, (0 - step).toUInt())
}
open class UIntProgression(val first: UInt, val last: UInt, val step: Int) {
    fun isEmpty(): Boolean = if (step > 0) first > last else first < last
    fun contains(x: UInt): Boolean {
        if (isEmpty()) return false
        if (step > 0) { if (x < first || x > last) return false; return (x - first) % step.toUInt() == 0u }
        if (x > first || x < last) return false
        return (first - x) % (0 - step).toUInt() == 0u
    }
    override fun toString(): String = if (step > 0) "" + first + ".." + last + " step " + step else "" + first + " downTo " + last + " step " + (0 - step)
    fun reversed(): UIntProgression = UIntProgression(last, first, 0 - step)
    fun count(): Int {
        if (isEmpty()) return 0
        if (step > 0) return ((last - first) / step.toUInt() + 1u).toInt()
        return ((first - last) / (0 - step).toUInt() + 1u).toInt()
    }
    fun toList(): List<UInt> {
        val r = ArrayList<UInt>()
        for (x in this) r.add(x)
        return r
    }
}
class UIntRange(start: UInt, endInclusive: UInt) : UIntProgression(start, endInclusive, 1) {
    val start: UInt = start
    val endInclusive: UInt = endInclusive
    override fun toString(): String = "" + first + ".." + last
}
fun kt_rangeToU(a: UInt, b: UInt): UIntRange = UIntRange(a, b)
fun kt_untilU(a: UInt, b: UInt): UIntRange = if (b == 0u) UIntRange(1u, 0u) else UIntRange(a, b - 1u)
fun kt_downToU(a: UInt, b: UInt): UIntProgression = UIntProgression(a, b, 0 - 1)
fun kt_stepU(p: UIntProgression, n: Int): UIntProgression {
    if (n <= 0) throw IllegalArgumentException("Step must be positive, was: " + n + ".")
    val s = if (p.step > 0) n else 0 - n
    return UIntProgression(p.first, kt_lastU(p.first, p.last, s), s)
}

fun kt_diffUL(a: ULong, b: ULong, c: ULong): ULong { val ac = a % c; val bc = b % c; return if (ac >= bc) ac - bc else ac - bc + c }
fun kt_lastUL(start: ULong, end: ULong, step: Long): ULong {
    if (step > 0L) { if (start >= end) return end; return end - kt_diffUL(end, start, step.toULong()) }
    if (start <= end) return end
    return end + kt_diffUL(start, end, (0L - step).toULong())
}
open class ULongProgression(val first: ULong, val last: ULong, val step: Long) {
    fun isEmpty(): Boolean = if (step > 0L) first > last else first < last
    fun contains(x: ULong): Boolean {
        if (isEmpty()) return false
        if (step > 0L) { if (x < first || x > last) return false; return (x - first) % step.toULong() == 0uL }
        if (x > first || x < last) return false
        return (first - x) % (0L - step).toULong() == 0uL
    }
    override fun toString(): String = if (step > 0L) "" + first + ".." + last + " step " + step else "" + first + " downTo " + last + " step " + (0L - step)
    fun reversed(): ULongProgression = ULongProgression(last, first, 0L - step)
    fun count(): Int {
        if (isEmpty()) return 0
        if (step > 0L) return ((last - first) / step.toULong() + 1uL).toInt()
        return ((first - last) / (0L - step).toULong() + 1uL).toInt()
    }
    fun toList(): List<ULong> {
        val r = ArrayList<ULong>()
        for (x in this) r.add(x)
        return r
    }
}
class ULongRange(start: ULong, endInclusive: ULong) : ULongProgression(start, endInclusive, 1L) {
    val start: ULong = start
    val endInclusive: ULong = endInclusive
    override fun toString(): String = "" + first + ".." + last
}
fun kt_rangeToUL(a: ULong, b: ULong): ULongRange = ULongRange(a, b)
fun kt_untilUL(a: ULong, b: ULong): ULongRange = if (b == 0uL) ULongRange(1uL, 0uL) else ULongRange(a, b - 1uL)
fun kt_downToUL(a: ULong, b: ULong): ULongProgression = ULongProgression(a, b, 0L - 1L)
fun kt_stepUL(p: ULongProgression, n: Long): ULongProgression {
    if (n <= 0L) throw IllegalArgumentException("Step must be positive, was: " + n + ".")
    val s = if (p.step > 0L) n else 0L - n
    return ULongProgression(p.first, kt_lastUL(p.first, p.last, s), s)
}
"""

/** Standard exception classes (`message` only); a missing message argument is passed as null. */
private val EXCEPTION_CLASSES = """
open class Throwable(val message: String?)
open class Exception(message: String?) : Throwable(message)
open class Error(message: String?) : Throwable(message)
open class RuntimeException(message: String?) : Exception(message)
open class IllegalStateException(message: String?) : RuntimeException(message)
open class IllegalArgumentException(message: String?) : RuntimeException(message)
open class UnsupportedOperationException(message: String?) : RuntimeException(message)
open class ArithmeticException(message: String?) : RuntimeException(message)
open class IndexOutOfBoundsException(message: String?) : RuntimeException(message)
open class ArrayIndexOutOfBoundsException(message: String?) : IndexOutOfBoundsException(message)
open class NoSuchElementException(message: String?) : RuntimeException(message)
open class UninitializedPropertyAccessException(message: String?) : RuntimeException(message)
open class NullPointerException(message: String?) : RuntimeException(message)
open class ClassCastException(message: String?) : RuntimeException(message)
open class NumberFormatException(message: String?) : IllegalArgumentException(message)
open class ConcurrentModificationException(message: String?) : RuntimeException(message)
open class AssertionError(message: String?) : Error(message)
open class NotImplementedError(message: String?) : Error(message)
"""

/** Exception class names declared by the prelude (constructors may omit the message). */
val PRELUDE_EXCEPTIONS = setOf("Throwable", "Exception", "Error", "RuntimeException", "IllegalStateException", "IllegalArgumentException", "UnsupportedOperationException", "ArithmeticException", "IndexOutOfBoundsException", "ArrayIndexOutOfBoundsException", "NoSuchElementException", "NullPointerException", "ClassCastException", "NumberFormatException", "ConcurrentModificationException", "AssertionError", "NotImplementedError")

/** Progression class name -> element kind suffix of its `kt_*` functions (I, L, C). */
val PROGRESSIONS = mapOf("UIntProgression" to "U", "UIntRange" to "U", "ULongProgression" to "UL", "ULongRange" to "UL", "IntProgression" to "I", "IntRange" to "I", "LongProgression" to "L", "LongRange" to "L",
    "CharProgression" to "C", "CharRange" to "C")

private val UNSIGNED_USE = Regex("""\bU(Int|Long)\b|\d[uU]""")
private val RANGE_USE = Regex("""\.\.|\brangeTo\b|\buntil\b|\bdownTo\b|\bstep\b|Range\b|Progression\b""")

private val EXC_USE = Regex("""\btry\b|\bassertFails""")

/**
 * Merges the prelude into [p] when the source may use it. Exception classes are included for programs with
 * `try` (or `assertFailsWith`); their presence switches the backend to catchable exceptions.
 */
/** `Pair`, `Triple` and `to`. */
const val PAIRS = """
data class Pair<A, B>(val first: A, val second: B) {
    override fun toString(): String = "(" + first + ", " + second + ")"
}
data class Triple<A, B, C>(val first: A, val second: B, val third: C) {
    override fun toString(): String = "(" + first + ", " + second + ", " + third + ")"
}
infix fun <A, B> A.to(that: B): Pair<A, B> = Pair(this, that)
"""
private val PAIR_USE = Regex("\\bPair\\b|\\bTriple\\b|\\bto\\b")

const val COMPARABLE = """
interface Comparable<T> { fun compareTo(other: T): Int }
"""
private val COMPARABLE_USE = Regex("\\bComparable\\b")

/** Common list operations as generic extensions (monomorphized per element type). */
const val LIST_EXT = """
fun <T> ArrayList<T>.indexOf(x: T): Int { var i = 0; val n = size; while (i < n) { if (get(i) == x) return i; i++ }; return -1 }
fun <T> ArrayList<T>.lastIndexOf(x: T): Int { var i = size - 1; while (i >= 0) { if (get(i) == x) return i; i-- }; return -1 }
fun <T> ArrayList<T>.contains(x: T): Boolean = indexOf(x) >= 0
fun <T> ArrayList<T>.remove(x: T): Boolean { val i = indexOf(x); if (i < 0) return false; removeAt(i); return true }
fun <T> ArrayList<T>.isEmpty(): Boolean = size == 0
fun <T> ArrayList<T>.isNotEmpty(): Boolean = size != 0
fun <T> ArrayList<T>.clear() { while (size > 0) removeAt(size - 1) }
fun <T> ArrayList<T>.first(): T { if (size == 0) throw NoSuchElementException("List is empty."); return get(0) }
fun <T> ArrayList<T>.last(): T { if (size == 0) throw NoSuchElementException("List is empty."); return get(size - 1) }
fun <T> ArrayList<T>.firstOrNull(): T? = if (size == 0) null else get(0)
fun <T> ArrayList<T>.lastOrNull(): T? = if (size == 0) null else get(size - 1)
data class IndexedValue<T>(val index: Int, val value: T)
fun <T> ArrayList<T>.withIndex(): ArrayList<IndexedValue<T>> { val r = ArrayList<IndexedValue<T>>(); var i = 0; for (x in this) { r.add(IndexedValue(i, x)); i++ }; return r }
"""
private val LIST_USE = Regex("""\.(indexOf|lastIndexOf|contains|remove|isEmpty|isNotEmpty|clear|first|last|firstOrNull|lastOrNull|withIndex)\(|-=|\bin\b""")

/** `indices` / `lastIndex` of lists, arrays and strings as extension properties. */
const val INDICES = """
val <T> ArrayList<T>.indices: IntRange get() = IntRange(0, size - 1)
val <T> ArrayList<T>.lastIndex: Int get() = size - 1
val <T> Array<T>.indices: IntRange get() = IntRange(0, size - 1)
val <T> Array<T>.lastIndex: Int get() = size - 1
val IntArray.indices: IntRange get() = IntRange(0, size - 1)
val IntArray.lastIndex: Int get() = size - 1
val String.indices: IntRange get() = IntRange(0, length - 1)
val String.lastIndex: Int get() = length - 1
"""
private val INDICES_USE = Regex("""\.(indices|lastIndex)\b""")

/** `Array(n) { init }`, `List(n) { }`, `MutableList(n) { }`: arrays of references are lists. */
const val LIST_INIT = """
fun <T> Array(size: Int, init: (Int) -> T): ArrayList<T> { val r = ArrayList<T>(); var i = 0; while (i < size) { r.add(init(i)); i++ }; return r }
fun <T> List(size: Int, init: (Int) -> T): ArrayList<T> { val r = ArrayList<T>(); var i = 0; while (i < size) { r.add(init(i)); i++ }; return r }
fun <T> MutableList(size: Int, init: (Int) -> T): ArrayList<T> { val r = ArrayList<T>(); var i = 0; while (i < size) { r.add(init(i)); i++ }; return r }
"""
private val LIST_INIT_USE = Regex("""\b(Array|List|MutableList)\s*(<[^>]*>)?\s*\(""")

fun withPrelude(src: String, p: Program): Program {
    val idx = INDICES_USE.containsMatchIn(src); val ranges = idx || RANGE_USE.containsMatchIn(src); val exc = EXC_USE.containsMatchIn(src); val pairs = PAIR_USE.containsMatchIn(src)
    val cmp = COMPARABLE_USE.containsMatchIn(src); val lists = LIST_USE.containsMatchIn(src); val linit = LIST_INIT_USE.containsMatchIn(src)
    if (!ranges && !exc && !pairs && !cmp && !lists && !linit) return p
    val text = (if (ranges) INT_PROGRESSION + LONG_PROGRESSION + CHAR_PROGRESSION + (if (UNSIGNED_USE.containsMatchIn(src)) UNSIGNED_PROGRESSION else "") else "") + (if (exc) EXCEPTION_CLASSES else "") + (if (pairs) PAIRS else "") + (if (cmp) COMPARABLE else "") + (if (lists) LIST_EXT else "") + (if (idx) INDICES else "") + (if (linit) LIST_INIT else "")
    val lib = Parser(Lexer(text).lex()).program()
    val taken = p.funs.map { it.name }.toSet() + p.classes.map { it.name }
    return Program(p.funs + lib.funs.filter { it.name !in taken }, p.classes + lib.classes.filter { it.name !in taken },
        p.ifaces + lib.ifaces.filter { i -> p.ifaces.none { it.name == i.name } }, p.globals + lib.globals, p.companions + lib.companions)
}

/** Primitive types that are boxed (into `AnyBox<T>` classes) when used as `Any`. */
val ANY_BOXED = listOf("Int", "Long", "Double", "Boolean", "Char", "String", "UInt", "ULong", "Byte")
private val ANY_USE = Regex("""\bAny\b""")

/**
 * `Any`: a built-in interface with no methods that every class implements. Primitives and strings are boxed into
 * `AnyBox<T>` classes. `anyToString__` / `anyEquals__` dispatch over the concrete (non-generic) classes.
 */
fun withAny(src: String, p: Program): Program {
    if (!ANY_USE.containsMatchIn(src)) return p
    val boxes = ANY_BOXED.joinToString("\n") { "class AnyBox$it(val v: $it)" }
    val lib0 = Parser(Lexer(boxes).lex()).program()
    val all = p.classes + lib0.classes
    val byName = all.associateBy { it.name }
    fun depth(c: ClassD): Int = c.superClass?.let { s -> byName[s]?.let { 1 + depth(it) } } ?: 0
    fun has(c: ClassD, m: String): Boolean = c.methods.any { it.name == m && it.params.size <= 1 } || (c.superClass?.let { byName[it] }?.let { has(it, m) } ?: false)
    for (c in p.classes) if (c.methods.any { it.name == "\$equals" }) {
        val sup = c.superClass?.let { byName[it] }
        if (sup != null && listOf("toString", "equals", "hashCode").any { has(sup, it) })
            throw CompileError("line ${c.line}: data class ${c.name} with a superclass overriding toString/equals/hashCode is not supported")
    }
    val concrete = p.classes.filter { it.typeParams.isEmpty() }.sortedByDescending { depth(it) }
    val sb = StringBuilder()
    sb.append("fun anyToString__(x: Any?): String {\n    if (x == null) return \"null\"\n")
    for (b in ANY_BOXED) sb.append("    if (x is AnyBox$b) return \"\${x.v}\"\n")
    for (c in concrete) when {
        c.enumEntries != null -> sb.append("    if (x is `${c.name}`) return x.name\n")
        c.isValue -> sb.append("    if (x is `${c.name}`) return \"\${x}\"\n")
        c.methods.any { it.name == "\$equals" } -> sb.append("    if (x is `${c.name}`) return x.toString()\n")
        has(c, "toString") -> sb.append("    if (x is `${c.name}`) return x.toString()\n")
        else -> sb.append("    if (x is `${c.name}`) return \"${c.name.substringAfterLast('$')}\"\n")
    }
    sb.append("    return \"Any\"\n}\n")
    sb.append("fun anyEquals__(a: Any?, b: Any?): Boolean {\n    if (a == null) return b == null\n    if (b == null) return false\n")
    for (b in ANY_BOXED) sb.append(if (b == "Double") "    if (a is AnyBoxDouble) return b is AnyBoxDouble && a.v.compareTo(b.v) == 0\n" else "    if (a is AnyBox$b) return b is AnyBox$b && a.v == b.v\n")
    for (c in concrete) when {
        has(c, "equals") -> sb.append("    if (a is `${c.name}`) return a.equals(b)\n")
        c.isValue || c.methods.any { it.name == "\$equals" } -> sb.append("    if (a is `${c.name}`) return b is `${c.name}` && a == b\n")
    }
    sb.append("    return false\n}\n")
    sb.append("fun anyHashCode__(x: Any?): Int {\n    if (x == null) return 0\n")
    for (b in ANY_BOXED) if (b != "UInt" && b != "ULong" && b != "Byte") sb.append("    if (x is AnyBox$b) return x.v.hashCode()\n")
    for (c in concrete) if (has(c, "hashCode") || c.methods.any { it.name == "\$equals" }) sb.append("    if (x is `${c.name}`) return x.hashCode()\n")
    sb.append("    return 0\n}\n")
    val lib = Parser(Lexer("interface Any\n" + sb).lex()).program()
    fun withAnySuper(c: ClassD) = if ("Any" in c.supers) c else
        ClassD(c.name, c.fields, c.supers + "Any", c.methods, c.line, c.ctorParams, c.inits, c.superArgs, c.typeParams, c.enumEntries, c.isValue, c.superTypeArgs)
    return Program(p.funs + lib.funs, all.map { withAnySuper(it) }, p.ifaces + lib.ifaces, p.globals, p.companions)
}
