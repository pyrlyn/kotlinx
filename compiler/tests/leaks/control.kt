// Leak test: objects across early return, when branches and function arguments.
class P(val x: Long, val y: Long)

fun pick(a: P, b: P): Long = if (a.x > b.x) a.y else b.y

fun find(limit: Long): Long {
    var i = 0L
    while (true) {
        val p = P(i, i + 1)
        if (p.x * 3 > limit) return p.y
        i++
    }
}

fun main() {
    var sum = 0L
    for (i in 0 until 200) {
        val a = P(i.toLong(), 1L)
        val b = P(5L, 2L)
        if (i % 7 != 0) sum += pick(a, b)
        sum += when (i % 3) {
            0 -> P(1L, 1L).x
            1 -> P(2L, 2L).y
            else -> 3L
        }
    }
    sum += find(1000L)
    println(sum)
}
