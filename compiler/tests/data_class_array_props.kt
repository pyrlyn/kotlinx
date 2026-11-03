data class Box(val xs: Array<String>, val n: Int)

fun main() {
    val a = arrayOf("a", "b")
    val b = arrayOf("a", "b")
    val x = Box(a, 1)
    println(x == Box(a, 1))
    println(x == Box(b, 1))
    println(x == x.copy(n = 2))
    println(x.hashCode() == Box(a, 1).hashCode())
    val (xs, n) = x
    println("${xs[1]} $n")
}
