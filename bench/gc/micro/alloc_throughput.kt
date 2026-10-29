// GC micro: allocation throughput: 20M small acyclic objects, each dead right after use.
class P(val x: Long, val y: Long)
fun main() {
    val t0 = kotlin.system.getTimeNanos()
    var s = 0L
    var i = 0L
    while (i < 20_000_000L) { val p = P(i, i + 1); s += p.x + p.y; i++ }
    val t1 = kotlin.system.getTimeNanos()
    println("checksum=$s nanos=${t1 - t0}")
}
