fun check(x: Int): Int {
    if (x < 0) throw IllegalArgumentException("negative: $x")
    return x * 2
}

fun pick(b: Boolean): String = if (b) "yes" else throw IllegalStateException()

fun main() {
    println(check(4))
    println(pick(true))
    val v = when (3) { 3 -> "three"; else -> throw AssertionError(3) }
    println(v)
    println(check(-1))
    println("unreachable")
}
