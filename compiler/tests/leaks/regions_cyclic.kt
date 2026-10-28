// Region test (slow path): possibly cyclic classes (var fields on a field cycle) go to the tracing
// region; the collector reclaims their cycles and visits only tracing-region objects, while the
// acyclic payloads they hold stay in the RC region.
class Payload(val v: Long)
class DNode(val key: Long, var prev: DNode?, var next: DNode?, val payload: Payload)

fun ring(n: Long): Long {
    val head = DNode(0L, null, null, Payload(0L))
    var cur = head
    var i = 1L
    while (i < n) {
        val x = DNode(i, cur, null, Payload(i))
        cur.next = x
        cur = x
        i++
    }
    cur.next = head
    head.prev = cur
    return cur.key + head.payload.v
}

fun main() {
    var acc = 0L
    for (k in 0 until 5000) acc += ring(8L)
    val keep = DNode(1L, null, null, Payload(5L))
    keep.next = DNode(2L, keep, keep, Payload(6L))
    for (k in 0 until 15000) { val p = Payload(k.toLong()); acc += p.v }
    acc += keep.next!!.payload.v
    println(acc)
}
