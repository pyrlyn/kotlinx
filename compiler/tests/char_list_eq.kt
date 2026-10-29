// Structural equality of Char lists.
fun main() {
    val l = mutableListOf<Char>()
    for (c in 'd' downTo 'a') l += c
    println(listOf('d', 'c', 'b', 'a') == l)
    println(listOf('a') == l)
    println(listOf('d', 'c', 'b', 'x') != l)
}
