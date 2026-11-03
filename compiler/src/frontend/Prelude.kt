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

/** Standard exception classes (`message`, plus `cause` on Throwable); a missing message argument is passed as null. */
private val EXCEPTION_CLASSES = """
open class Throwable(val message: String?, val cause: Throwable? = null)
open class Exception(message: String?) : Throwable(message, null)
open class Error(message: String?) : Throwable(message, null)
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

// A class extending a standard exception (`class E : Throwable("m")`) needs the prelude classes too.
private val EXC_USE = Regex("""\btry\b|\bassertFails|:\s*(Throwable|Exception|Error|[A-Z]\w*(Exception|Error))\s*\(""")

/**
 * Merges the prelude into [p] when the source may use it. Exception classes are included for programs with
 * `try` (or `assertFailsWith`); their presence switches the backend to catchable exceptions.
 */
/** `Result<T>`: a success value or a failure exception (a class, not an inline value class). */
const val RESULT = """
class Result<T>(val value__: T?, val exc__: Throwable?) {
    val isSuccess: Boolean get() = exc__ == null
    val isFailure: Boolean get() = exc__ != null
    fun getOrNull(): T? = if (exc__ == null) value__ else null
    fun exceptionOrNull(): Throwable? = exc__
    fun getOrThrow(): T { val e = exc__; if (e != null) throw e; return value__ as T }
    fun getOrDefault(defaultValue: T): T = if (exc__ == null) value__ as T else defaultValue
    fun <R> fold(onSuccess: (T) -> R, onFailure: (Throwable) -> R): R { val e = exc__; return if (e == null) onSuccess(value__ as T) else onFailure(e) }
    override fun toString(): String = if (exc__ == null) "Success(" + value__ + ")" else "Failure(" + exc__ + ")"
    override fun equals(other: Any?): Boolean = other is Result<*> && other.toString() == toString()
    override fun hashCode(): Int = toString().hashCode()
    companion object {
        fun <T> success(value: T): Result<T> = Result<T>(value, null)
        fun <T> failure(exception: Throwable): Result<T> = Result<T>(null, exception)
    }
}
fun <T> runCatching(block: () -> T): Result<T> {
    try { return Result<T>(block(), null) } catch (e: Throwable) { return Result<T>(null, e) }
}
"""
private val RESULT_USE = Regex("""\bResult\b|\brunCatching\b""")

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
// A sequence over a list is the list itself here (eager operations; iteration order and values are the same).
fun <T> ArrayList<T>.asSequence(): ArrayList<T> = this
fun <T> ArrayList<T>.forEachIndexed(action: (Int, T) -> Unit) { var i = 0; for (x in this) { action(i, x); i++ } }
fun <T> ArrayList<T>.indexOfFirst(predicate: (T) -> Boolean): Int { var i = 0; for (x in this) { if (predicate(x)) return i; i++ }; return -1 }
fun <T> ArrayList<T>.indexOfLast(predicate: (T) -> Boolean): Int { var i = size - 1; while (i >= 0) { if (predicate(get(i))) return i; i-- }; return -1 }
fun <T> ArrayList<T>.addAll(xs: ArrayList<T>): Boolean { for (x in xs) add(x); return xs.size != 0 }
data class IndexedValue<T>(val index: Int, val value: T)
fun <T> ArrayList<T>.withIndex(): ArrayList<IndexedValue<T>> { val r = ArrayList<IndexedValue<T>>(); var i = 0; for (x in this) { r.add(IndexedValue(i, x)); i++ }; return r }
fun String.withIndex(): ArrayList<IndexedValue<Char>> { val r = ArrayList<IndexedValue<Char>>(); var i = 0; for (x in this) { r.add(IndexedValue(i, x)); i++ }; return r }
fun IntArray.withIndex(): ArrayList<IndexedValue<Int>> { val r = ArrayList<IndexedValue<Int>>(); var i = 0; for (x in this) { r.add(IndexedValue(i, x)); i++ }; return r }
fun LongArray.withIndex(): ArrayList<IndexedValue<Long>> { val r = ArrayList<IndexedValue<Long>>(); var i = 0; for (x in this) { r.add(IndexedValue(i, x)); i++ }; return r }
fun ByteArray.withIndex(): ArrayList<IndexedValue<Byte>> { val r = ArrayList<IndexedValue<Byte>>(); var i = 0; for (x in this) { r.add(IndexedValue(i, x)); i++ }; return r }
fun DoubleArray.withIndex(): ArrayList<IndexedValue<Double>> { val r = ArrayList<IndexedValue<Double>>(); var i = 0; for (x in this) { r.add(IndexedValue(i, x)); i++ }; return r }
fun FloatArray.withIndex(): ArrayList<IndexedValue<Float>> { val r = ArrayList<IndexedValue<Float>>(); var i = 0; for (x in this) { r.add(IndexedValue(i, x)); i++ }; return r }
fun CharArray.withIndex(): ArrayList<IndexedValue<Char>> { val r = ArrayList<IndexedValue<Char>>(); var i = 0; for (x in this) { r.add(IndexedValue(i, x)); i++ }; return r }
fun BooleanArray.withIndex(): ArrayList<IndexedValue<Boolean>> { val r = ArrayList<IndexedValue<Boolean>>(); var i = 0; for (x in this) { r.add(IndexedValue(i, x)); i++ }; return r }
fun UIntArray.withIndex(): ArrayList<IndexedValue<UInt>> { val r = ArrayList<IndexedValue<UInt>>(); var i = 0; for (x in this) { r.add(IndexedValue(i, x)); i++ }; return r }
fun ULongArray.withIndex(): ArrayList<IndexedValue<ULong>> { val r = ArrayList<IndexedValue<ULong>>(); var i = 0; for (x in this) { r.add(IndexedValue(i, x)); i++ }; return r }
"""
/** Copies of primitive arrays: `reversedArray()`, `copyOf()`, `contentToString()`, `toList()`, `reversed()` (also for lists), `iterator()` (arrays and lists). */
const val ARRAY_EXT = """
fun IntArray.reversedArray(): IntArray { val n = size; val r = IntArray(n); var i = 0; while (i < n) { r[i] = this[n - 1 - i]; i++ }; return r }
fun IntArray.copyOf(): IntArray { val n = size; val r = IntArray(n); var i = 0; while (i < n) { r[i] = this[i]; i++ }; return r }
fun LongArray.reversedArray(): LongArray { val n = size; val r = LongArray(n); var i = 0; while (i < n) { r[i] = this[n - 1 - i]; i++ }; return r }
fun LongArray.copyOf(): LongArray { val n = size; val r = LongArray(n); var i = 0; while (i < n) { r[i] = this[i]; i++ }; return r }
fun ByteArray.reversedArray(): ByteArray { val n = size; val r = ByteArray(n); var i = 0; while (i < n) { r[i] = this[n - 1 - i]; i++ }; return r }
fun ByteArray.copyOf(): ByteArray { val n = size; val r = ByteArray(n); var i = 0; while (i < n) { r[i] = this[i]; i++ }; return r }
fun DoubleArray.reversedArray(): DoubleArray { val n = size; val r = DoubleArray(n); var i = 0; while (i < n) { r[i] = this[n - 1 - i]; i++ }; return r }
fun DoubleArray.copyOf(): DoubleArray { val n = size; val r = DoubleArray(n); var i = 0; while (i < n) { r[i] = this[i]; i++ }; return r }
fun FloatArray.reversedArray(): FloatArray { val n = size; val r = FloatArray(n); var i = 0; while (i < n) { r[i] = this[n - 1 - i]; i++ }; return r }
fun FloatArray.copyOf(): FloatArray { val n = size; val r = FloatArray(n); var i = 0; while (i < n) { r[i] = this[i]; i++ }; return r }
fun CharArray.reversedArray(): CharArray { val n = size; val r = CharArray(n); var i = 0; while (i < n) { r[i] = this[n - 1 - i]; i++ }; return r }
fun CharArray.copyOf(): CharArray { val n = size; val r = CharArray(n); var i = 0; while (i < n) { r[i] = this[i]; i++ }; return r }
fun BooleanArray.reversedArray(): BooleanArray { val n = size; val r = BooleanArray(n); var i = 0; while (i < n) { r[i] = this[n - 1 - i]; i++ }; return r }
fun BooleanArray.copyOf(): BooleanArray { val n = size; val r = BooleanArray(n); var i = 0; while (i < n) { r[i] = this[i]; i++ }; return r }
fun UIntArray.reversedArray(): UIntArray { val n = size; val r = UIntArray(n); var i = 0; while (i < n) { r[i] = this[n - 1 - i]; i++ }; return r }
fun UIntArray.copyOf(): UIntArray { val n = size; val r = UIntArray(n); var i = 0; while (i < n) { r[i] = this[i]; i++ }; return r }
fun ULongArray.reversedArray(): ULongArray { val n = size; val r = ULongArray(n); var i = 0; while (i < n) { r[i] = this[n - 1 - i]; i++ }; return r }
fun ULongArray.copyOf(): ULongArray { val n = size; val r = ULongArray(n); var i = 0; while (i < n) { r[i] = this[i]; i++ }; return r }
fun IntArray.contentToString(): String { var s = "["; var i = 0; for (x in this) { if (i > 0) s += ", "; s += x; i++ }; return s + "]" }
fun IntArray.toList(): ArrayList<Int> { val r = ArrayList<Int>(); for (x in this) r.add(x); return r }
fun LongArray.contentToString(): String { var s = "["; var i = 0; for (x in this) { if (i > 0) s += ", "; s += x; i++ }; return s + "]" }
fun LongArray.toList(): ArrayList<Long> { val r = ArrayList<Long>(); for (x in this) r.add(x); return r }
fun ByteArray.contentToString(): String { var s = "["; var i = 0; for (x in this) { if (i > 0) s += ", "; s += x; i++ }; return s + "]" }
fun ByteArray.toList(): ArrayList<Byte> { val r = ArrayList<Byte>(); for (x in this) r.add(x); return r }
fun DoubleArray.contentToString(): String { var s = "["; var i = 0; for (x in this) { if (i > 0) s += ", "; s += x; i++ }; return s + "]" }
fun DoubleArray.toList(): ArrayList<Double> { val r = ArrayList<Double>(); for (x in this) r.add(x); return r }
fun FloatArray.contentToString(): String { var s = "["; var i = 0; for (x in this) { if (i > 0) s += ", "; s += x; i++ }; return s + "]" }
fun FloatArray.toList(): ArrayList<Float> { val r = ArrayList<Float>(); for (x in this) r.add(x); return r }
fun CharArray.contentToString(): String { var s = "["; var i = 0; for (x in this) { if (i > 0) s += ", "; s += x; i++ }; return s + "]" }
fun CharArray.toList(): ArrayList<Char> { val r = ArrayList<Char>(); for (x in this) r.add(x); return r }
fun BooleanArray.contentToString(): String { var s = "["; var i = 0; for (x in this) { if (i > 0) s += ", "; s += x; i++ }; return s + "]" }
fun BooleanArray.toList(): ArrayList<Boolean> { val r = ArrayList<Boolean>(); for (x in this) r.add(x); return r }
fun UIntArray.contentToString(): String { var s = "["; var i = 0; for (x in this) { if (i > 0) s += ", "; s += x; i++ }; return s + "]" }
fun UIntArray.toList(): ArrayList<UInt> { val r = ArrayList<UInt>(); for (x in this) r.add(x); return r }
fun ULongArray.contentToString(): String { var s = "["; var i = 0; for (x in this) { if (i > 0) s += ", "; s += x; i++ }; return s + "]" }
fun ULongArray.toList(): ArrayList<ULong> { val r = ArrayList<ULong>(); for (x in this) r.add(x); return r }
fun IntArray.reversed(): ArrayList<Int> { val r = ArrayList<Int>(); var i = size - 1; while (i >= 0) { r.add(this[i]); i-- }; return r }
fun LongArray.reversed(): ArrayList<Long> { val r = ArrayList<Long>(); var i = size - 1; while (i >= 0) { r.add(this[i]); i-- }; return r }
fun ByteArray.reversed(): ArrayList<Byte> { val r = ArrayList<Byte>(); var i = size - 1; while (i >= 0) { r.add(this[i]); i-- }; return r }
fun DoubleArray.reversed(): ArrayList<Double> { val r = ArrayList<Double>(); var i = size - 1; while (i >= 0) { r.add(this[i]); i-- }; return r }
fun FloatArray.reversed(): ArrayList<Float> { val r = ArrayList<Float>(); var i = size - 1; while (i >= 0) { r.add(this[i]); i-- }; return r }
fun CharArray.reversed(): ArrayList<Char> { val r = ArrayList<Char>(); var i = size - 1; while (i >= 0) { r.add(this[i]); i-- }; return r }
fun BooleanArray.reversed(): ArrayList<Boolean> { val r = ArrayList<Boolean>(); var i = size - 1; while (i >= 0) { r.add(this[i]); i-- }; return r }
fun UIntArray.reversed(): ArrayList<UInt> { val r = ArrayList<UInt>(); var i = size - 1; while (i >= 0) { r.add(this[i]); i-- }; return r }
fun ULongArray.reversed(): ArrayList<ULong> { val r = ArrayList<ULong>(); var i = size - 1; while (i >= 0) { r.add(this[i]); i-- }; return r }
fun <T> ArrayList<T>.reversed(): ArrayList<T> { val r = ArrayList<T>(); var i = size - 1; while (i >= 0) { r.add(get(i)); i-- }; return r }
fun <T> ArrayList<T>.toList(): ArrayList<T> { val r = ArrayList<T>(); for (x in this) r.add(x); return r }
class IntArrayIterator(val a: IntArray) { var i = 0; fun hasNext(): Boolean = i < a.size; fun next(): Int { val x = a[i]; i++; return x } }
fun IntArray.iterator(): IntArrayIterator = IntArrayIterator(this)
class LongArrayIterator(val a: LongArray) { var i = 0; fun hasNext(): Boolean = i < a.size; fun next(): Long { val x = a[i]; i++; return x } }
fun LongArray.iterator(): LongArrayIterator = LongArrayIterator(this)
class ByteArrayIterator(val a: ByteArray) { var i = 0; fun hasNext(): Boolean = i < a.size; fun next(): Byte { val x = a[i]; i++; return x } }
fun ByteArray.iterator(): ByteArrayIterator = ByteArrayIterator(this)
class DoubleArrayIterator(val a: DoubleArray) { var i = 0; fun hasNext(): Boolean = i < a.size; fun next(): Double { val x = a[i]; i++; return x } }
fun DoubleArray.iterator(): DoubleArrayIterator = DoubleArrayIterator(this)
class FloatArrayIterator(val a: FloatArray) { var i = 0; fun hasNext(): Boolean = i < a.size; fun next(): Float { val x = a[i]; i++; return x } }
fun FloatArray.iterator(): FloatArrayIterator = FloatArrayIterator(this)
class CharArrayIterator(val a: CharArray) { var i = 0; fun hasNext(): Boolean = i < a.size; fun next(): Char { val x = a[i]; i++; return x } }
fun CharArray.iterator(): CharArrayIterator = CharArrayIterator(this)
class BooleanArrayIterator(val a: BooleanArray) { var i = 0; fun hasNext(): Boolean = i < a.size; fun next(): Boolean { val x = a[i]; i++; return x } }
fun BooleanArray.iterator(): BooleanArrayIterator = BooleanArrayIterator(this)
class UIntArrayIterator(val a: UIntArray) { var i = 0; fun hasNext(): Boolean = i < a.size; fun next(): UInt { val x = a[i]; i++; return x } }
fun UIntArray.iterator(): UIntArrayIterator = UIntArrayIterator(this)
class ULongArrayIterator(val a: ULongArray) { var i = 0; fun hasNext(): Boolean = i < a.size; fun next(): ULong { val x = a[i]; i++; return x } }
fun ULongArray.iterator(): ULongArrayIterator = ULongArrayIterator(this)
class ListIterator__<T>(val a: ArrayList<T>) { var i = 0; fun hasNext(): Boolean = i < a.size; fun next(): T { val x = a.get(i); i++; return x } }
fun <T> ArrayList<T>.iterator(): ListIterator__<T> = ListIterator__(this)
operator fun StringBuilder.get(i: Int): Char = toString()[i]
fun StringBuilder.withIndex(): ArrayList<IndexedValue<Char>> { val r = ArrayList<IndexedValue<Char>>(); var i = 0; for (x in toString()) { r.add(IndexedValue(i, x)); i++ }; return r }
fun IntArray.contentEquals(o: IntArray): Boolean { if (size != o.size) return false; var i = 0; while (i < size) { if (this[i] != o[i]) return false; i++ }; return true }
fun LongArray.contentEquals(o: LongArray): Boolean { if (size != o.size) return false; var i = 0; while (i < size) { if (this[i] != o[i]) return false; i++ }; return true }
fun ByteArray.contentEquals(o: ByteArray): Boolean { if (size != o.size) return false; var i = 0; while (i < size) { if (this[i] != o[i]) return false; i++ }; return true }
fun DoubleArray.contentEquals(o: DoubleArray): Boolean { if (size != o.size) return false; var i = 0; while (i < size) { if (this[i] != o[i]) return false; i++ }; return true }
fun FloatArray.contentEquals(o: FloatArray): Boolean { if (size != o.size) return false; var i = 0; while (i < size) { if (this[i] != o[i]) return false; i++ }; return true }
fun CharArray.contentEquals(o: CharArray): Boolean { if (size != o.size) return false; var i = 0; while (i < size) { if (this[i] != o[i]) return false; i++ }; return true }
fun BooleanArray.contentEquals(o: BooleanArray): Boolean { if (size != o.size) return false; var i = 0; while (i < size) { if (this[i] != o[i]) return false; i++ }; return true }
fun UIntArray.contentEquals(o: UIntArray): Boolean { if (size != o.size) return false; var i = 0; while (i < size) { if (this[i] != o[i]) return false; i++ }; return true }
fun ULongArray.contentEquals(o: ULongArray): Boolean { if (size != o.size) return false; var i = 0; while (i < size) { if (this[i] != o[i]) return false; i++ }; return true }
fun <T> ArrayList<T>.contentEquals(o: ArrayList<T>): Boolean { if (size != o.size) return false; var i = 0; while (i < size) { if (get(i) != o.get(i)) return false; i++ }; return true }
"""
private val ARRAY_EXT_USE = Regex("""\.(reversedArray|copyOf|contentToString|toList|reversed|iterator|contentEquals)\(|[(,=]\s*\*\s*\w|\bvararg\b[^)]*=|\bStringBuilder\b""")
/** `zip` of two lists into pairs (needs the pair prelude). */
const val ZIP_EXT = """
fun <T, R> ArrayList<T>.zip(o: ArrayList<R>): ArrayList<Pair<T, R>> { val r = ArrayList<Pair<T, R>>(); val n = if (size < o.size) size else o.size; var i = 0; while (i < n) { r.add(Pair(get(i), o.get(i))); i++ }; return r }
"""
private val ZIP_USE = Regex("""\.zip\(""")
/** `withIndex()` of progressions (needs both the range and the list prelude). */
const val RANGE_LIST_EXT = """
fun IntProgression.withIndex(): ArrayList<IndexedValue<Int>> { val r = ArrayList<IndexedValue<Int>>(); var i = 0; for (x in this) { r.add(IndexedValue(i, x)); i++ }; return r }
"""
private val LIST_USE = Regex("""\.(indexOf|lastIndexOf|contains|remove|isEmpty|isNotEmpty|clear|first|last|firstOrNull|lastOrNull|withIndex|asSequence|forEachIndexed|indexOfFirst|indexOfLast|addAll)\(|-=|\bin\b""")

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

/** `arrayOfNulls<T>(n)`: arrays of references are lists; elements start as null. */
const val ARRAY_OF_NULLS = """
fun <T> arrayOfNulls(size: Int): ArrayList<T?> { val r = ArrayList<T?>(); var i = 0; while (i < size) { r.add(null); i++ }; return r }
"""
private val ARRAY_OF_NULLS_USE = Regex("""\barrayOfNulls\b""")

/** Scope functions taking a function value (literal lambdas of `let`/`also`/`run` are lowered in the backend). */
const val SCOPE_FUNS = """
fun <T> T.apply(block: T.() -> Unit): T { block(this); return this }
fun <T> T.also(block: (T) -> Unit): T { block(this); return this }
fun <T, R> T.let(block: (T) -> R): R = block(this)
fun <T, R> T.run(block: T.() -> R): R = block(this)
fun <T, R> with(receiver: T, block: T.() -> R): R = block(receiver)
"""
private val SCOPE_USE = Regex("""\.(apply)\s*[({]|\.(also|let|run)\s*\(|\bwith\s*\(""")

/** `lazy { }`: computes the value on first use (single-threaded; `by lazy(LazyThreadSafetyMode.X) { }` drops the mode).
 * `by lazy { }` reads `.value` (see Parser.delegated). */
const val LAZY = """
class Lazy<T>(val initializer: () -> T) {
    private var done = false
    private var cached: ArrayList<T> = ArrayList<T>()
    val value: T get() { if (!done) { cached.add(initializer()); done = true }; return cached.get(0) }
    fun isInitialized(): Boolean = done
    override fun toString(): String = if (done) "" + cached.get(0) else "Lazy value not initialized yet."
}
fun <T> lazy(initializer: () -> T): Lazy<T> = Lazy(initializer)
fun <T> lazyOf(value: T): Lazy<T> = Lazy({ value })
"""
private val LAZY_USE = Regex("""\blazy(Of)?\b""")

/** `Any()`: a plain object with identity equality (an empty class, typed as `Any`). */
const val ANY_OBJ = """
class KtAnyObject
fun Any(): Any = KtAnyObject()
"""
private val ANY_OBJ_USE = Regex("""\bAny\s*\(\s*\)""")

/** `maxOf` / `minOf` of two values (monomorphized per argument type). */
const val MIN_MAX = """
fun <T> maxOf(a: T, b: T): T = if (a >= b) a else b
fun <T> minOf(a: T, b: T): T = if (a <= b) a else b
"""
private val MIN_MAX_USE = Regex("""\b(maxOf|minOf)\s*\(""")

/** `emptyList<T>()` as an empty `ArrayList<T>`. */
const val EMPTY_LIST = """
fun <T> emptyList(): ArrayList<T> = ArrayList<T>()
"""
private val EMPTY_LIST_USE = Regex("""\bemptyList\s*<""")

/** Kotlin/Native memory model queries: KotlinX has one memory model, reported as the new ("experimental") one. */
const val MEMORY_MODEL = """
enum class MemoryModel { STRICT, RELAXED, EXPERIMENTAL }
object Platform {
    val memoryModel: MemoryModel get() = MemoryModel.EXPERIMENTAL
}
fun isExperimentalMM(): Boolean = true
"""
private val MEMORY_MODEL_USE = Regex("""\bisExperimentalMM\b|\bPlatform\.memoryModel\b|\bMemoryModel\.""")

/** `X::class`: a minimal KClass (names only; no other reflection). */
const val KCLASS = """
data class KClass(val simpleName: String?, val qualifiedName: String?) {
    override fun toString(): String = "class " + (qualifiedName ?: simpleName ?: "")
}
"""
private val KCLASS_USE = Regex("""::\s*class\b""")

fun withPrelude(src: String, p: Program): Program {
    val idx = INDICES_USE.containsMatchIn(src); val ranges = idx || RANGE_USE.containsMatchIn(src); val result = RESULT_USE.containsMatchIn(src); val exc = result || EXC_USE.containsMatchIn(src); val zip = ZIP_USE.containsMatchIn(src); val pairs = zip || PAIR_USE.containsMatchIn(src)
    val cmp = COMPARABLE_USE.containsMatchIn(src); val arrx = ARRAY_EXT_USE.containsMatchIn(src); val lists = arrx || LIST_USE.containsMatchIn(src); val linit = LIST_INIT_USE.containsMatchIn(src); val scope = SCOPE_USE.containsMatchIn(src); val aon = ARRAY_OF_NULLS_USE.containsMatchIn(src); val lz = LAZY_USE.containsMatchIn(src); val anyObj = ANY_OBJ_USE.containsMatchIn(src); val mm = MIN_MAX_USE.containsMatchIn(src); val el = EMPTY_LIST_USE.containsMatchIn(src); val mmod = MEMORY_MODEL_USE.containsMatchIn(src); val kcls = KCLASS_USE.containsMatchIn(src)
    if (!arrx && !kcls && !ranges && !exc && !pairs && !cmp && !lists && !linit && !scope && !aon && !lz && !anyObj && !mm && !el && !mmod) return p
    val text = (if (ranges) INT_PROGRESSION + LONG_PROGRESSION + CHAR_PROGRESSION + (if (UNSIGNED_USE.containsMatchIn(src)) UNSIGNED_PROGRESSION else "") else "") + (if (exc) EXCEPTION_CLASSES else "") + (if (result) RESULT else "") + (if (pairs) PAIRS else "") + (if (zip) ZIP_EXT else "") + (if (cmp) COMPARABLE else "") + (if (lists) LIST_EXT else "") + (if (arrx) ARRAY_EXT else "") + (if (lists && ranges) RANGE_LIST_EXT else "") + (if (idx) INDICES else "") + (if (linit) LIST_INIT else "") + (if (scope) SCOPE_FUNS else "") + (if (aon) ARRAY_OF_NULLS else "") + (if (lz) LAZY else "") + (if (anyObj) ANY_OBJ else "") + (if (mm) MIN_MAX else "") + (if (el) EMPTY_LIST else "") + (if (mmod) MEMORY_MODEL else "") + (if (kcls) KCLASS else "")
    val lib = Parser(Lexer(text).lex()).program()
    val taken = p.funs.map { it.name }.toSet() + p.classes.map { it.name }
    return Program(p.funs + lib.funs.filter { it.name !in taken }, p.classes + lib.classes.filter { it.name !in taken },
        p.ifaces + lib.ifaces.filter { i -> p.ifaces.none { it.name == i.name } }, p.globals + lib.globals, p.companions + lib.companions)
}

/** Primitive types that are boxed (into `AnyBox<T>` classes) when used as `Any`. */
val ANY_BOXED = listOf("Int", "Long", "Double", "Float", "Boolean", "Char", "String", "UInt", "ULong", "Byte")
/** Primitive arrays as `Any` (boxed; equality is identity, as in Kotlin). */
val ANY_BOXED_ARRAYS = listOf("IntArray", "LongArray", "DoubleArray", "FloatArray", "BooleanArray", "CharArray", "UIntArray", "ULongArray", "ByteArray")
// Nullable primitives (`Int?`) and safe calls (`a?.x` may yield one) are boxed like `Any`.
// A nullable type parameter (`T?`, short names) may be instantiated with a primitive, which is boxed too.
private val ANY_USE = Regex("""\bAny\b|\b(Int|Long|Double|Float|Boolean|Char|UInt|ULong|Byte|Short|UByte|UShort)\?|\?\.|\b[A-Z]\?|\bResult\b|\brunCatching\b|\bNumber\b""")

/**
 * `Any`: a built-in interface with no methods that every class implements. Primitives and strings are boxed into
 * `AnyBox<T>` classes. `anyToString__` / `anyEquals__` dispatch over the concrete (non-generic) classes.
 */
fun withAny(src: String, p: Program): Program {
    if (!ANY_USE.containsMatchIn(src)) return p
    // Lists (and `Array<T>`, also a list here) as `Any`: a box per primitive/String element type, and one for lists of
// objects with the element type erased (as on the JVM).
    val boxes = (ANY_BOXED + ANY_BOXED_ARRAYS).joinToString("\n") { "class AnyBox$it(val v: $it)" } + "\nclass AnyBoxList(val v: ArrayList<Any>)\n" +
        ANY_BOXED.joinToString("\n") { "class AnyBoxList$it(val v: ArrayList<$it>)" }
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
    for (b in ANY_BOXED) sb.append(if (b == "Double" || b == "Float") "    if (a is AnyBox$b) return b is AnyBox$b && a.v.compareTo(b.v) == 0\n" else "    if (a is AnyBox$b) return b is AnyBox$b && a.v == b.v\n")
    for (c in concrete) when {
        has(c, "equals") -> sb.append("    if (a is `${c.name}`) return a.equals(b)\n")
        c.isValue || c.methods.any { it.name == "\$equals" } -> sb.append("    if (a is `${c.name}`) return b is `${c.name}` && a == b\n")
    }
    for (b in ANY_BOXED_ARRAYS) sb.append("    if (a is AnyBox$b) return b is AnyBox$b && a.v === b.v\n")
    for (b in listOf("") + ANY_BOXED) sb.append("    if (a is AnyBoxList$b) return b is AnyBoxList$b && a.v === b.v\n")
    sb.append("    return false\n}\n")
    sb.append("fun anyHashCode__(x: Any?): Int {\n    if (x == null) return 0\n")
    for (b in ANY_BOXED) if (b != "UInt" && b != "ULong" && b != "Byte") sb.append("    if (x is AnyBox$b) return x.v.hashCode()\n")
    for (c in concrete) if (has(c, "hashCode") || c.methods.any { it.name == "\$equals" }) sb.append("    if (x is `${c.name}`) return x.hashCode()\n")
    sb.append("    return 0\n}\n")
    // `Number` is `Any`; its conversions dispatch over the boxed numeric types.
    if (Regex("""\bNumber\b""").containsMatchIn(src)) for ((f, t) in listOf("Int" to "Int", "Long" to "Long", "Double" to "Double", "Float" to "Float", "Byte" to "Byte", "Short" to "Short")) {
        sb.append("fun numTo${f}__(x: Any): $t {\n")
        for (b in listOf("Int", "Long", "Double", "Float", "Byte")) sb.append("    if (x is AnyBox$b) return x.v.to$f()\n")
        sb.append("    throw ClassCastException(\"not a Number\")\n}\n")
    }
    val lib = Parser(Lexer("interface Any\n" + sb).lex()).program()
    fun withAnySuper(c: ClassD) = if ("Any" in c.supers) c else
        ClassD(c.name, c.fields, c.supers + "Any", c.methods, c.line, c.ctorParams, c.inits, c.superArgs, c.typeParams, c.enumEntries, c.isValue, c.superTypeArgs)
    return Program(p.funs + lib.funs, all.map { withAnySuper(it) }, p.ifaces + lib.ifaces, p.globals, p.companions)
}
