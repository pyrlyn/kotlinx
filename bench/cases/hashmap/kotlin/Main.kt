// hashmap: 1M Int->Long inserts, then 3M lookups (1/3 hits) with the standard HashMap.
@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)
@file:Suppress("DEPRECATION_ERROR")

fun main() {
    val n = 1_000_000
    val t0 = kotlin.system.getTimeNanos()
    val m = HashMap<Int, Long>()
    for (i in 0 until n) m[i * 3] = i.toLong() * 2
    var sum = 0L
    var miss = 0L
    for (j in 0 until 3 * n) {
        if (m.containsKey(j)) sum += m[j]!! else miss++
    }
    val checksum = sum + miss * 1_000_003 + m.size
    val t1 = kotlin.system.getTimeNanos()
    println("checksum=$checksum nanos=${t1 - t0}")
}
