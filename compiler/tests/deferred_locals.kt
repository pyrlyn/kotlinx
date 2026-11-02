// Local properties declared without an initializer and assigned later.
class Box(val v: Int)

fun pick(c: Boolean): String {
    val res: String
    if (c) res = "yes" else res = "no"
    return res
}

fun main() {
    println(pick(true) + " " + pick(false))
    val n: Long
    n = 40L + 2
    println(n)
    var i: Int?
    i = 5
    println(i)
    val b: Box
    when (n) { 42L -> b = Box(1); else -> b = Box(2) }
    println(b.v)
    val items: List<String>
    items = listOf("a", "b")
    println(items.size)
}
