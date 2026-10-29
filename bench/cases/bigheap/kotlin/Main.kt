// bigheap: a large long-lived heap made of many deep cyclic object graphs, churned, plus acyclic traffic.
// 2000 live graphs, each a ring of 200 nodes; every node points to a payload and back to its owning graph
// (graph -> head -> ... -> node -> owner = graph), so ~800k objects stay live in cycles.
// 200 rounds x 50 replacements: a random graph is replaced by a new one (the old ring becomes cyclic garbage),
// the new graph is walked, and a short-lived acyclic chain of 100 leaves is built and summed.
// Kotlin Native: tracing GC; Kotlin-X compiler: RC + cycle collector; Rust: Rc/RefCell + Weak owner, rings broken by hand;
// Zig: c_allocator create/destroy.
@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)
@file:Suppress("DEPRECATION_ERROR")

class Payload(val v: Long)
class Graph(val id: Long, var head: Node?)
class Node(val id: Long, var next: Node?, val owner: Graph, val p: Payload)
class Leaf(val v: Long, val next: Leaf?)

fun build(seed: Long, size: Int): Graph {
    val g = Graph(seed, null)
    val first = Node(seed * 1000L, null, g, Payload(seed * 3L))
    var cur = first
    var i = 1
    while (i < size) {
        val n = Node(seed * 1000L + i, null, g, Payload(seed * 3L + i))
        cur.next = n
        cur = n
        i++
    }
    cur.next = first
    g.head = first
    return g
}

fun walk(g: Graph, steps: Int): Long {
    var s = 0L
    var cur: Node? = g.head
    var i = 0
    while (i < steps) {
        val c = cur
        if (c == null) return s
        s += c.id + c.p.v + c.owner.id
        cur = c.next
        i++
    }
    return s
}

fun acyclic(seed: Long, n: Int): Long {
    var l: Leaf? = null
    var i = 0
    while (i < n) {
        l = Leaf(seed + i, l)
        i++
    }
    var s = 0L
    var cur = l
    while (cur != null) {
        s += cur.v
        cur = cur.next
    }
    return s
}

fun main() {
    val t0 = kotlin.system.getTimeNanos()
    val count = 2000
    val graphs = ArrayList<Graph?>()
    for (g in 0 until count) graphs.add(build(g.toLong(), 200))
    var x = 12345L
    var checksum = 0L
    var seed = count.toLong()
    for (round in 0 until 200) {
        for (k in 0 until 50) {
            x = (x * 6364136223846793005L + 1442695040888963407L)
            val idx = ((x ushr 33) % count.toLong()).toInt()
            val g = build(seed, 200)
            graphs[idx] = g
            checksum += walk(g, 50)
            checksum += acyclic(seed, 100)
            seed++
        }
    }
    for (g in 0 until count) {
        val gr = graphs[g]
        if (gr != null) checksum += walk(gr, 10)
    }
    val t1 = kotlin.system.getTimeNanos()
    println("checksum=$checksum nanos=${t1 - t0}")
}
