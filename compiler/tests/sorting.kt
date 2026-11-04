// sorted/sortedDescending/sortedBy/sortedByDescending and in-place sortBy (stable merge sort).
data class P(val name: String, val age: Int)
fun main() {
    val l = arrayListOf(3, 1, 2)
    l.sort(); println(l)
    println(listOf("b", "a", "c").sorted())
    println(l.sortedDescending())
    val ps = arrayListOf(P("x", 30), P("y", 20), P("z", 30), P("w", 10))
    println(ps.sortedBy { it.age }.map { it.name })
    println(ps.sortedByDescending { it.age }.map { it.name })
    ps.sortBy { it.name }; println(ps.map { it.name })
    println(listOf("ccc", "a", "bb").sortedBy { it.length })
}
