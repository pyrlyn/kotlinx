// Former compiler crashes: trimMargin with a blank prefix throws at run time.
fun main() {
    try {
        "a b c".trimMargin(" ")
        println("not thrown")
    } catch (e: IllegalArgumentException) {
        println("caught: ${e.message}")
    }
    println("""
        |x
        |y""".trimMargin())
}
