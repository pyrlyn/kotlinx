// free: allocation and deallocation patterns. Each round fills a list with 200k objects of three
// sizes, replaces every third one (freeing the old one), then drops the whole list.
@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)
@file:Suppress("DEPRECATION_ERROR")

interface Obj { fun v(): Long }
class S1(val a: Long) : Obj { override fun v(): Long = a }
class S3(val a: Long, val b: Long, val c: Long) : Obj { override fun v(): Long = a + b + c }
class S6(val a: Long, val b: Long, val c: Long, val d: Long, val e: Long, val f: Long) : Obj { override fun v(): Long = a + b + c + d + e + f }

fun mk(i: Long): Obj = when (i % 3L) {
    0L -> S1(i)
    1L -> S3(i, 1L, 2L)
    else -> S6(i, 1L, 2L, 3L, 4L, 5L)
}

fun main() {
    val n = 200_000
    val t0 = kotlin.system.getTimeNanos()
    var checksum = 0L
    for (r in 0 until 20) {
        val xs = ArrayList<Obj>()
        for (i in 0 until n) xs.add(mk(i.toLong() + r))
        var i = 0
        while (i < n) { xs[i] = mk(i.toLong() * 7); i += 3 }
        for (o in xs) checksum += o.v()
    }
    val t1 = kotlin.system.getTimeNanos()
    println("checksum=$checksum nanos=${t1 - t0}")
}
