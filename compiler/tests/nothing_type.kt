// Explicit `Nothing` return types: calls never return, so they fit any expected type; `null!!` is Nothing too.
fun fail(msg: String): Nothing = throw IllegalStateException(msg)
fun npe(): Nothing = null!!

fun classify(x: Any): Int = when (x) {
    is String -> 0
    is Int -> 1
    else -> fail("other")
}

fun orFail(s: String?): String = s ?: fail("null")

fun main() {
    println(classify("a") + classify(2))
    try { classify(1.5) } catch (e: IllegalStateException) { println("caught " + e.message) }
    println(orFail("ok"))
    try { orFail(null) } catch (e: IllegalStateException) { println("caught " + e.message) }
    try { npe() } catch (e: NullPointerException) { println("npe") }
    val v: String
    if (classify(1) == 1) v = "assigned" else fail("unreachable")
    println(v)
}
