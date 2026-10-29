// Type checks and casts on owned temporaries and locals: as/as? results kept or dropped, failed casts releasing
// their operand (including on a caught ClassCastException), and smart-cast aliases not double-releasing.
open class Node(val v: Int)
class Leaf(v: Int, val tag: String) : Node(v)
class Pair2(v: Int, val l: Node, val r: Node) : Node(v)

fun mk(i: Int): Node = if (i % 3 == 0) Leaf(i, "t" + i) else if (i % 3 == 1) Pair2(i, Leaf(1, "a"), Node(2)) else Node(i)

fun weight(n: Node): Int = when (n) {
    is Leaf -> n.tag.length
    is Pair2 -> weight(n.l) + weight(n.r)
    else -> 1
}

fun main() {
    var acc = 0
    val keep = ArrayList<Leaf>()
    for (i in 0 until 300) {
        acc += weight(mk(i))
        val x = mk(i) as? Leaf
        if (x != null) keep.add(x)
        if (mk(i) is Pair2) acc++
        val n = mk(i)
        if (n !is Leaf) continue
        acc += n.tag.length
        try {
            val p = mk(i) as Pair2
            acc += p.v
        } catch (e: ClassCastException) {
            acc += 100
        }
    }
    println(acc)
    println(keep.size)
}
