// Leak test: reference cycles reclaimed by the cycle collector (trial deletion): self loops,
// two-node cycles, rings, cycles holding acyclic objects and lists, and cycles kept alive from outside.
class Leaf(val v: Long)
class Node(val id: Long, var next: Node?, val leaf: Leaf, val tags: ArrayList<Leaf>)
interface Peer { fun id(): Long }
class A(val n: Long, var peer: Peer?) : Peer { override fun id(): Long = n }
class B(val n: Long, var peer: Peer?) : Peer { override fun id(): Long = n * 10 }

fun ring(size: Long): Long {
    val first = Node(0L, null, Leaf(1L), ArrayList<Leaf>())
    var cur = first
    var i = 1L
    while (i < size) {
        val tags = ArrayList<Leaf>()
        tags.add(Leaf(i))
        val n = Node(i, null, Leaf(i), tags)
        cur.next = n
        cur = n
        i++
    }
    cur.next = first
    return cur.id + first.leaf.v
}

fun main() {
    var sum = 0L
    for (k in 0 until 3000) {
        val self = Node(k.toLong(), null, Leaf(2L), ArrayList<Leaf>())
        self.next = self
        val a = A(k.toLong(), null)
        val b = B(k.toLong(), a)
        a.peer = b
        sum += self.id + a.id() + b.id() + ring(5L)
    }
    // A cycle that stays reachable must survive collections.
    val keep = Node(7L, null, Leaf(7L), ArrayList<Leaf>())
    keep.next = Node(8L, keep, Leaf(8L), ArrayList<Leaf>())
    for (k in 0 until 20000) { val x = Node(1L, null, Leaf(1L), ArrayList<Leaf>()); x.next = x; sum += x.id }
    sum += keep.id + keep.leaf.v
    println(sum)
}
