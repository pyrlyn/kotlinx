// matmul: 256x256 DoubleArray matrix multiply (i-k-j order), 4 repetitions.
@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)
@file:Suppress("DEPRECATION_ERROR")

fun main() {
    val n = 256
    val a = DoubleArray(n * n)
    val b = DoubleArray(n * n)
    val c = DoubleArray(n * n)
    for (i in 0 until n * n) {
        a[i] = (i % 7).toDouble()
        b[i] = (i % 5).toDouble() - 2.0
    }
    val t0 = kotlin.system.getTimeNanos()
    var total = 0.0
    for (rep in 0 until 4) {
        for (i in 0 until n * n) c[i] = 0.0
        for (i in 0 until n) {
            for (k in 0 until n) {
                val aik = a[i * n + k] + rep
                for (j in 0 until n) c[i * n + j] += aik * b[k * n + j]
            }
        }
        for (i in 0 until n * n) total += c[i]
    }
    val checksum = total.toLong()
    val t1 = kotlin.system.getTimeNanos()
    println("checksum=$checksum nanos=${t1 - t0}")
}
