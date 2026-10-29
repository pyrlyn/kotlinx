// cycles: build rings of 100 nodes (each node also points to an acyclic payload), walk them, drop them.
// 20k rings. Kotlin Native: tracing GC; KotlinX compiler: cycle collector; Rust/Zig: rings broken/freed by hand.
@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)
@file:Suppress("DEPRECATION_ERROR")

class Payload(val v: Long)
class Node(val id: Long, var next: Node?, val p: Payload)

fun ring(seed: Long, size: Int): Node {
    val first = Node(seed, null, Payload(seed * 3L))
    var cur = first
    var i = 1
    while (i < size) {
        val n = Node(seed + i, null, Payload((seed + i) * 3L))
        cur.next = n
        cur = n
        i++
    }
    cur.next = first
    return first
}

fun walk(first: Node, steps: Int): Long {
    var s = 0L
    var cur: Node? = first
    var i = 0
    while (i < steps) {
        val c = cur
        if (c == null) return s
        s += c.id + c.p.v
        cur = c.next
        i++
    }
    return s
}

fun main() {
    val t0 = kotlin.system.getTimeNanos()
    var checksum = 0L
    for (r in 0 until 20_000) {
        val first = ring(r.toLong(), 100)
        checksum += walk(first, 150)
    }
    val t1 = kotlin.system.getTimeNanos()
    println("checksum=$checksum nanos=${t1 - t0}")
}
