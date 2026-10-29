class V(val x: Int, val y: Int) {
    operator fun plus(o: V): V = V(x + o.x, y + o.y)
    operator fun minus(o: V): V = V(x - o.x, y - o.y)
    operator fun times(k: Int): V = V(x * k, y * k)
    operator fun div(k: Int): V = V(x / k, y / k)
    operator fun rem(k: Int): V = V(x % k, y % k)
    operator fun unaryMinus(): V = V(-x, -y)
    operator fun compareTo(o: V): Int = (x * x + y * y) - (o.x * o.x + o.y * o.y)
    fun str(): String = "($x, $y)"
}

class Grid(val w: Int) {
    val cells = IntArray(w * w)
    operator fun get(i: Int): Int = cells[i]
    operator fun set(i: Int, v: Int) { cells[i] = v }
}

class Flag(val on: Boolean) {
    operator fun not(): Flag = Flag(!on)
}

fun main() {
    val a = V(1, 2)
    val b = V(3, 4)
    println((a + b).str())
    println((b - a).str())
    println((a * 3).str())
    println((b / 2).str())
    println((b % 2).str())
    println((-a).str())
    println(a < b)
    println(a >= b)
    var acc = V(0, 0)
    for (i in 1..3) acc += V(i, i)
    println(acc.str())
    acc -= a
    println(acc.str())
    val g = Grid(3)
    g[4] = 7
    g[4] += 2
    println(g[4] + g[0])
    println((!Flag(true)).on)
    println((a + b * 2 - a).str())
}
