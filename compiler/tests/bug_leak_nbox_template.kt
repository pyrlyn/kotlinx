// Regression: a nullable primitive call result in a string template (a temporary box) leaked (found by --leak-check).
fun f(b: Boolean): Int? = if (b) 5 else null
fun g(): Long? = 7L
fun main() {
    val x = f(true)
    println("s=${f(true)} ${f(false)} $x ${g()}")
    for (i in 0 until 3) println("i=${f(i % 2 == 0)}")
    println(f(true)); println(g())
}
