// alloc: ROUNDS x N individually heap-allocated small objects, summed, then dropped (GC).
@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)
@file:Suppress("DEPRECATION_ERROR")

class P(val x: Long, val y: Long)

fun main() {
    val rounds = 50L
    val n = 200_000L
    val t0 = kotlin.system.getTimeNanos()
    var checksum = 0L
    var r = 0L
    while (r < rounds) {
        val v = ArrayList<P>()
        var i = 0L
        while (i < n) { v.add(P(i + r, (i * 7) % 1000)); i++ }
        for (p in v) checksum += p.x * 31 + p.y
        r++
    }
    val t1 = kotlin.system.getTimeNanos()
    println("checksum=$checksum nanos=${t1 - t0}")
}
