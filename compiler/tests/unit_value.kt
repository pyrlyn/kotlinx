// Unit as a value.
fun f(b: Boolean): Unit { if (b) return Unit; println("no") }
fun g(x: Int) = when (x) { 1 -> println("one"); else -> Unit }
fun main() { f(true); f(false); g(1); g(2); println("done") }
