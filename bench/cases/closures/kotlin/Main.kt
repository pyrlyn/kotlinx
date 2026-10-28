// closures: lambda-heavy map/filter/sumOf/forEach over 1M Longs, 20 rounds, capturing locals.
@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)
@file:Suppress("DEPRECATION_ERROR")

fun main() {
    val n = 1_000_000
    val xs = ArrayList<Long>()
    for (i in 0 until n) xs.add(i.toLong())
    val t0 = kotlin.system.getTimeNanos()
    var total = 0L
    var r = 0
    while (r < 20) {
        val k = (r % 5 + 1).toLong()
        val ys = xs.map { it * k + r }
        val zs = ys.filter { x -> x % 3 == 0L }
        total += zs.sumOf { it and 1023L }
        zs.forEach { total += it % 7 }
        r++
    }
    val t1 = kotlin.system.getTimeNanos()
    println("checksum=$total nanos=${t1 - t0}")
}
