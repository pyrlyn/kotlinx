// trees: binary-trees style. Short-lived trees of depth 4..16 (step 2), many iterations each,
// plus one long-lived tree; checksum = total node count visited.
@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)
@file:Suppress("DEPRECATION_ERROR")

class Tree(val l: Tree?, val r: Tree?)

fun make(d: Int): Tree = if (d == 0) Tree(null, null) else Tree(make(d - 1), make(d - 1))

fun check(t: Tree?): Long = if (t == null) 0L else 1L + check(t.l) + check(t.r)

fun main() {
    val minD = 4
    val maxD = 16
    val t0 = kotlin.system.getTimeNanos()
    val longLived = make(maxD)
    var checksum = 0L
    var d = minD
    while (d <= maxD) {
        val iters = 1 shl (maxD - d + minD)
        var i = 0
        while (i < iters) {
            val t = make(d)
            checksum += check(t)
            i++
        }
        d += 2
    }
    checksum += check(longLived)
    val t1 = kotlin.system.getTimeNanos()
    println("checksum=$checksum nanos=${t1 - t0}")
}
