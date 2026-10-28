// Leak test: Perceus part 2. Stack-allocated non-escaping objects, and reuse of a dying
// object's cell by a constructor (unique: reused in place; shared: dropped, fresh cell).
class Vec(val x: Double, val y: Double) {
    fun dot(o: Double): Double = x * o + y * o
    fun len2(): Double = x * x + y * y
}
class P(val a: Long, val b: Long)
class Cell(val v: Long, val inner: P)
class Other(val w: Long, val p: P)

fun step(c: Cell): Long {
    val n = Cell(c.v + 1, c.inner)
    return n.v + n.inner.a
}

fun main() {
    var total = 0.0
    for (i in 0 until 1000) {
        val v = Vec(i.toDouble(), 2.0)
        total += v.len2() + v.dot(0.5) + v.x
    }
    var acc = 0L
    val keep = ArrayList<Cell>()
    for (i in 0 until 1000) {
        val c1 = Cell(i.toLong(), P(1L, 2L))
        if (i % 3 == 0) keep.add(c1)
        val c2 = Cell(c1.v + 1, c1.inner)
        val o = Other(c2.v * 2, c2.inner)
        acc += o.w + o.p.b + step(Cell(5L, P(3L, 4L)))
    }
    for (c in keep) acc += c.v + c.inner.a
    println(total)
    println(acc)
}
