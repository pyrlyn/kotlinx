@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)
@file:Suppress("DEPRECATION_ERROR")

fun main() {
    val n = 10_000_000
    var sum = 0L
    val t0 = kotlin.system.getTimeNanos()
    var i = 1
    while (i <= n) {
        val sq = i.toLong() * i
        if (i % 3 == 0) sum += sq else sum -= sq
        i++
    }
    val t1 = kotlin.system.getTimeNanos()
    println("sum=$sum nanos=${t1 - t0}")
}
