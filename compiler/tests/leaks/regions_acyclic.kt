// Region test (fast path): only acyclic classes. Every object lives in the RC region, is freed the
// instant its count reaches zero, and the cycle collector never runs or visits anything.
class Point(val x: Long, val y: Long)
class Seg(val a: Point, val b: Point)
class Tree(val left: Tree?, val right: Tree?, val v: Long)

fun build(d: Long): Tree? {
    if (d == 0L) return null
    return Tree(build(d - 1L), build(d - 1L), d)
}
fun sum(t: Tree?): Long = if (t == null) 0L else t.v + sum(t.left) + sum(t.right)

fun main() {
    var acc = 0L
    for (i in 0 until 20000) {
        val s = Seg(Point(i.toLong(), 1L), Point(2L, i.toLong()))
        acc += s.a.x + s.b.y
    }
    for (i in 0 until 50) acc += sum(build(10L))
    println(acc)
}
