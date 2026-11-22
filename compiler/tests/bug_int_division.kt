// Integer division follows Kotlin: MIN_VALUE / -1 wraps, % -1 is 0, a zero divisor throws ArithmeticException.
fun div(a: Int, b: Int) = a / b
fun rem(a: Int, b: Int) = a % b
fun ldiv(a: Long, b: Long) = a / b
fun lrem(a: Long, b: Long) = a % b
fun udiv(a: UInt, b: UInt) = a / b

fun main() {
    val m1 = "-1".toInt()
    println(div(Int.MIN_VALUE, m1))
    println(rem(Int.MIN_VALUE, m1))
    println(ldiv(Long.MIN_VALUE, m1.toLong()))
    println(lrem(Long.MIN_VALUE, m1.toLong()))
    println(Int.MIN_VALUE / -1)
    println(Int.MIN_VALUE % -1)
    println(div(7, m1))
    println(rem(-7, 2))
    println(div(-7, 2))
    var x = Int.MIN_VALUE
    x /= m1
    println(x)
    val zero = "0".toInt()
    try { println(div(1, zero)) } catch (e: ArithmeticException) { println("div: " + e.message) }
    try { println(rem(1, zero)) } catch (e: ArithmeticException) { println("rem: " + e.message) }
    try { println(ldiv(1L, zero.toLong())) } catch (e: ArithmeticException) { println("ldiv: " + e.message) }
    try { println(udiv(1u, zero.toUInt())) } catch (e: ArithmeticException) { println("udiv: " + e.message) }
    println(div(1, zero))
}
