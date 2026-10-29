package newkotlin

/**
 * Library code written in New Kotlin itself, merged into a program that uses it: ranges and progressions as values
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

/** Progression class name -> element kind suffix of its `kt_*` functions (I, L, C). */
val PROGRESSIONS = mapOf("IntProgression" to "I", "IntRange" to "I", "LongProgression" to "L", "LongRange" to "L",
    "CharProgression" to "C", "CharRange" to "C")

private val RANGE_USE = Regex("""\.\.|\buntil\b|\bdownTo\b|\bstep\b|Range\b|Progression\b""")

/** Merges the prelude into [p] when the source may use it. */
fun withPrelude(src: String, p: Program): Program {
    if (!RANGE_USE.containsMatchIn(src)) return p
    val lib = Parser(Lexer(INT_PROGRESSION + LONG_PROGRESSION + CHAR_PROGRESSION).lex()).program()
    val taken = p.funs.map { it.name }.toSet() + p.classes.map { it.name }
    return Program(p.funs + lib.funs.filter { it.name !in taken }, p.classes + lib.classes.filter { it.name !in taken },
        p.ifaces + lib.ifaces, p.globals + lib.globals, p.companions + lib.companions)
}
