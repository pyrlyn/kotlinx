// Operator overloading on reference types: temporaries from chained operators, compound assignment replacing
// a var, operator results stored in fields and lists, get/set operators on an object holding references.
class V(val x: Int, val y: Int) {
    operator fun plus(o: V): V = V(x + o.x, y + o.y)
    operator fun times(k: Int): V = V(x * k, y * k)
    operator fun unaryMinus(): V = V(-x, -y)
    operator fun compareTo(o: V): Int = x - o.x
}

class Store {
    val items = ArrayList<V>()
    operator fun get(i: Int): V = items[i]
    operator fun set(i: Int, v: V) { if (i < items.size) items[i] = v else items.add(v) }
}

class Holder(var v: V)

fun main() {
    var acc = V(0, 0)
    val h = Holder(V(1, 1))
    val st = Store()
    var sum = 0L
    for (i in 0 until 1000) {
        acc += V(i, 1) * 2 + -V(1, 0)
        h.v = h.v + V(1, 0)
        st[i % 5] = V(i, i) + V(1, 1)
        if (st[0] < acc) sum += 1
        sum += (V(i, 0) + V(0, i) + V(1, 1)).x
    }
    println("${acc.x} ${acc.y} ${h.v.x} ${st[4].x} $sum")
}
