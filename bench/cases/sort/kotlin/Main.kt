// sort: fill 1M Longs from an LCG and sort them with the same hand-written quicksort.
@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)
@file:Suppress("DEPRECATION_ERROR")

fun qsort(a: LongArray, lo: Int, hi: Int) {
    if (lo >= hi) return
    val p = a[(lo + hi) ushr 1]
    var i = lo
    var j = hi
    while (i <= j) {
        while (a[i] < p) i++
        while (a[j] > p) j--
        if (i <= j) {
            val t = a[i]
            a[i] = a[j]
            a[j] = t
            i++
            j--
        }
    }
    if (lo < j) qsort(a, lo, j)
    if (i < hi) qsort(a, i, hi)
}

fun main() {
    val n = 1_000_000
    val t0 = kotlin.system.getTimeNanos()
    val a = LongArray(n)
    var x = 12345L
    for (i in 0 until n) {
        x = x * 6364136223846793005L + 1442695040888963407L
        a[i] = x ushr 20
    }
    qsort(a, 0, n - 1)
    var checksum = 0L
    var bad = 0L
    for (i in 0 until n) {
        checksum = checksum * 31 + a[i]
        if (i > 0 && a[i - 1] > a[i]) bad++
    }
    checksum += bad * 1_000_000_007L
    val t1 = kotlin.system.getTimeNanos()
    println("checksum=$checksum nanos=${t1 - t0}")
}
