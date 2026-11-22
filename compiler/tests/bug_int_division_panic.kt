// Without try/catch in the program (no exception support compiled in) a zero divisor still stops with ArithmeticException.
fun div(a: Int, b: Int) = a / b
fun main() {
    println(div(Int.MIN_VALUE, "-1".toInt()))
    println(div(1, "0".toInt()))
    println("unreachable")
}
