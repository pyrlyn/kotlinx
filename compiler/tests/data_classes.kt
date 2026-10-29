// Data classes: toString, structural ==, hashCode, copy with named arguments, componentN and destructuring; Pair/Triple/to.
data class P(val x: Int, val name: String)
data class Box<T>(val v: T)
data class Line(val a: P, val b: P)

fun main() {
    val p = P(1, "a")
    val q = P(1, "a")
    println(p)
    println(p == q)
    println(p != P(2, "a"))
    println(p.equals(q))
    println(p.hashCode() == q.hashCode())
    println("abc".hashCode())
    println(p.hashCode())
    val r = p.copy(name = "b")
    println(r)
    println(p.copy())
    val (x, n) = r
    println("$x $n")
    println(Box("s") == Box("s"))
    println(Box(3))
    println(Line(p, r))
    println(Line(p, r) == Line(q, P(1, "b")))
    val pr = 1 to "one"
    println(pr)
    println(pr.first)
    val (a, b) = pr
    println("$a/$b")
    for ((i, s) in listOf(P(5, "five"), P(6, "six"))) println("$i=$s")
    val (u, _, w) = listOf(7, 8, 9)
    println(u + w)
    println(Triple(1, 'c', 2.5))
    println(true.hashCode())
    println(10L.hashCode())
}
