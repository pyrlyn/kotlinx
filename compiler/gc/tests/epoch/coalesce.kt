// Epoch GC: a release cancels an earlier retain of the same object in the current log (KX_GC_COALESCE,
// default on). Run by tests/run.sh: coalesced > 0 with it on, 0 with KX_GC_COALESCE=0, same checksum, and
// --leak-check frees everything both ways. Long-lived cyclic nodes passed around (retain/release pairs),
// next to cyclic garbage that must still be collected.
class N(val id: Long, var next: N?)
fun touch(n: N): Long = n.id + (n.next?.id ?: 0L)
fun main() {
    val ring = ArrayList<N>()
    for (i in 0 until 1000) ring.add(N(i.toLong(), null))
    for (i in 0 until 1000) ring[i].next = ring[(i + 1) % 1000]
    var s = 0L
    for (k in 0 until 300_000) {
        val n = ring[k % 1000]
        s += touch(n) + touch(n)
        val a = N(1L, null); val b = N(2L, a); a.next = b
        s += touch(a)
        if (k % 1000 == 0) ring[k % 1000 / 7].next = ring[(k * 31) % 1000]
    }
    println("checksum=$s")
}
