// Generic functions over reference types: owned temporaries as arguments, returning a borrowed parameter,
// generic containers built and dropped, nested generic calls, and a discarded generic result.
class Node(val v: Int, var next: Node?)

fun <T> id(x: T): T = x

fun <T> choose(c: Boolean, a: T, b: T): T = if (c) a else b

fun <T> fill(x: T, n: Int): ArrayList<T> {
    val r = ArrayList<T>()
    var i = 0
    while (i < n) { r.add(x); i++ }
    return r
}

fun <T> last(xs: ArrayList<T>): T = xs[xs.size - 1]

fun <T> wrap(x: T): ArrayList<T> = fill(id(x), 1)

fun main() {
    var sum = 0L
    for (i in 0 until 1000) {
        val n = id(Node(i, null))
        val m = choose(i % 2 == 0, n, Node(-i, n))
        sum += m.v
        val xs = fill(Node(i, Node(1, null)), 3)
        sum += last(xs).v + xs.size
        sum += last(wrap(m)).v
        id(Node(7, null))
        val s = id("s" + i)
        sum += s.length
        val ls = fill(fill(i, 2), 2)
        sum += last(last(ls))
    }
    println(sum)
}
