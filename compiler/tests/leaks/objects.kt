// Leak test: class instances created in loops, nested in fields, returned from functions, reassigned.
class P(val x: Long, val y: Long)
class Box(val p: P, val n: Long)

fun make(i: Long): P = P(i, i * 2)

fun main() {
    var sum = 0L
    var i = 0L
    while (i < 1000L) {
        val p = make(i)
        val b = Box(p, i)
        sum += b.p.x + b.n
        i++
    }
    var cur = P(0L, 0L)
    for (k in 0 until 100) { cur = P(k.toLong(), 1L); sum += cur.x }
    println(sum)
}
