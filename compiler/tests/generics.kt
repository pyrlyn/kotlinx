class Point(val x: Int, val y: Int)

fun <T> identity(x: T): T = x

fun <T> pick(c: Boolean, a: T, b: T): T = if (c) a else b

fun <T> repeatList(x: T, n: Int): ArrayList<T> {
    val r = ArrayList<T>()
    for (i in 0 until n) r.add(x)
    return r
}

fun <T> firstOf(xs: ArrayList<T>): T = xs[0]

fun <K, V> single(k: K, v: V): HashMap<K, V> {
    val m = HashMap<K, V>()
    m[k] = v
    return m
}

fun <T : Comparable<T>> maxOf3(a: T, b: T, c: T): T {
    var m = a
    if (b > m) m = b
    if (c > m) m = c
    return m
}

fun <T> describe(x: T): String = "<" + x + ">"

fun <T> twice(x: T): ArrayList<T> = repeatList(x, 2)

fun main() {
    println(identity(42))
    println(identity("hello"))
    println(identity(3000000000L))
    println(identity<Long>(7))
    println(pick(false, 1.5, 2.5))
    val p = pick(true, Point(1, 2), Point(3, 4))
    println(p.x + p.y)
    val xs = repeatList("ab", 3)
    println(xs.size)
    println(firstOf(xs))
    println(firstOf(repeatList(9, 2)))
    val m = single(7, 5L)
    println(m[7])
    println(maxOf3(3, 9, 4))
    println(maxOf3(2.0, 1.0, 0.5))
    println(describe(5))
    println(describe("s"))
    println(twice(Point(5, 6))[1].y)
}
