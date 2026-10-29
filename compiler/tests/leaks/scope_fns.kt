class P(var n: Int)
fun find(k: Int): String? = if (k > 0) "v$k" else null
fun main() {
    val x = "a".let { it + "b" }
    println(x)
    println(listOf(1, 2).also { println(it.size) }.size)
    repeat(3) { print(it) }
    println()
    repeat(2) { i -> print(i * 10) }
    println()
    println(run { val q = 4; q * q })
    find(2)?.let { s -> println(s.length) }
    find(0)?.let { s -> println(s.length) }
    val p = P(1).also { it.n += 5 }
    println(p.n)
    var total = 0
    for (k in 1..3) find(k)?.also { total += it.length }
    println(total)
}
