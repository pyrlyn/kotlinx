// Generic methods: owned temporaries passed to generic methods, results returned as new generic instances,
// generic functions taking generic class parameters, and discarded generic method results.
class Node(val v: Int, var next: Node?)

class Box<T>(val value: T) {
    fun <R> pairWith(r: R): Pair2<T, R> = Pair2(value, r)
    fun <R> replace(r: R): Box<R> = Box(r)
}

class Pair2<A, B>(val first: A, val second: B)

class Util {
    fun <T> twice(x: T): ArrayList<T> {
        val r = ArrayList<T>()
        r.add(x); r.add(x)
        return r
    }
}

fun <T> unbox(b: Box<T>): T = b.value

fun main() {
    var sum = 0L
    val u = Util()
    for (i in 0 until 1000) {
        val b = Box(Node(i, null))
        val p = b.pairWith(Node(1, Node(2, null)))
        sum += p.first.v + p.second.v
        sum += b.replace("s" + i).value.length
        sum += unbox(Box(Node(3, null))).v
        sum += u.twice(Node(i, null)).size
        u.twice("t" + i)
        b.pairWith(Node(4, null))
        sum += unbox(b.replace(Node(5, b.value))).next!!.v
    }
    println(sum)
}
