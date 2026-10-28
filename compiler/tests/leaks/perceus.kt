// Leak test: Perceus drop-at-last-use and moves (constructor args, list.add, return,
// var/field assignment, interface coercion, aliasing), including branches and loops.
interface Node { fun sum(): Long }
class Leaf(val v: Long) : Node { override fun sum(): Long = v }
class Pair2(val a: Node, val b: Node) : Node { override fun sum(): Long = a.sum() + b.sum() }
class Holder(var n: Node)

fun build(d: Long): Node {
    if (d == 0L) return Leaf(1L)
    val l = build(d - 1)
    val r = build(d - 1)
    val p = Pair2(l, r)
    return p
}

fun keep(n: Node): Long = n.sum()

fun main() {
    var total = 0L
    val t = build(8L)
    total += keep(t)
    val alias = t
    total += alias.sum() + t.sum()
    val h = Holder(Leaf(0L))
    for (i in 0 until 50) {
        val leaf = Leaf(i.toLong())
        if (i % 2 == 0) h.n = leaf else total += leaf.sum()
        val list = ArrayList<Node>()
        val x = Leaf(3L)
        list.add(x)
        val y = Pair2(Leaf(1L), Leaf(2L))
        list.add(y)
        var cur: Node = Leaf(0L)
        val z = Leaf(i.toLong() * 2)
        cur = z
        total += cur.sum() + list.sumOf { it.sum() }
    }
    total += h.n.sum()
    println(total)
}
