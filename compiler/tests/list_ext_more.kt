data class S(val name: String)

fun main() {
    val xs = listOf(S("a"), S("b"), S("a"))
    xs.forEachIndexed { i, s -> println("$i ${s.name}") }
    println("${xs.indexOfFirst { it.name == "a" }} ${xs.indexOfLast { it.name == "a" }} ${xs.indexOfFirst { it.name == "z" }}")
    val m = mutableListOf(1)
    println(m.addAll(listOf(2, 3)))
    println(m)
    val ns = listOf(5, 6)
    ns.forEachIndexed { i, n -> print(i + n) }
    println()
}
