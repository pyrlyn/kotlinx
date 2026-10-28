// Leak test: locals whose last use is an if/else are owned by the branches: moved into a field in
// one branch, unused in the other, used only in the condition, or returned early from a branch.
class Box(val v: Long)
class Holder(var a: Box?, var b: Box?)

fun put(h: Holder, x: Box, left: Boolean) {
    val y = x
    if (left) h.a = y else h.b = y
}

fun pick(h: Holder, x: Box, k: Long): Long {
    val y = Box(x.v + k)
    if (y.v % 3L == 0L) {
        h.a = y
        return y.v
    } else {
        if (k > 5L) h.b = y else return k
    }
    return 0L
}

fun condOnly(x: Box): Long {
    val y = Box(x.v * 2L)
    if (y.v > 10L) { println("big") } else { println("small") }
    return 1L
}

fun main() {
    val h = Holder(null, null)
    var s = 0L
    for (i in 0 until 1000) {
        val b = Box(i.toLong())
        put(h, b, i % 2 == 0)
        s += pick(h, b, (i % 9).toLong())
    }
    s += condOnly(Box(3L)) + condOnly(Box(30L))
    println("s=$s a=${h.a!!.v} b=${h.b!!.v}")
}
