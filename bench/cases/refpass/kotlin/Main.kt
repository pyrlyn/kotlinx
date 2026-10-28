// refpass: passing and storing references. 30M calls taking objects as arguments, local aliases,
// and stores into a mutable field (shared ownership), over a pool of 1000 objects.
@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)
@file:Suppress("DEPRECATION_ERROR")

class P(val x: Long, val y: Long)
class Holder(var p: P)

fun score(a: P, b: P): Long = a.x * b.y - a.y * b.x

fun pick(a: P, b: P, k: Long): P = if (k and 1L == 0L) a else b

fun main() {
    val pool = ArrayList<P>()
    for (i in 0 until 1000) pool.add(P(i.toLong(), (i * 7 % 13).toLong()))
    val h = Holder(pool[0])
    val t0 = kotlin.system.getTimeNanos()
    var checksum = 0L
    var k = 0L
    while (k < 30_000_000L) {
        val a = pool[(k % 1000L).toInt()]
        val b = h.p
        val c = pick(a, b, k)
        checksum += score(a, c)
        if (k % 4L == 0L) h.p = a
        k++
    }
    val t1 = kotlin.system.getTimeNanos()
    println("checksum=$checksum nanos=${t1 - t0}")
}
