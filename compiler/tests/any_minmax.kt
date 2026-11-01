fun pick(b: Boolean): Any {
    var r = Any()
    if (b) throw IllegalStateException("boom")
    return r
}

fun main() {
    val a = Any()
    val b = Any()
    println(a == a)
    println(a == b)
    pick(false)
    try { pick(true) } catch (e: IllegalStateException) { println("caught " + e.message) }
    println(maxOf(10, 17))
    println(minOf(3L, 2L))
    println(maxOf(1.5, 0.5))
    println(minOf("b", "a"))
}
