class Counter(val base: Int) {
    fun total(n: Int): Int {
        fun step(i: Int): Int = base + i
        var s = 0
        for (i in 0 until n) s += step(i)
        return s
    }
}

fun fact(n: Int): Long {
    fun go(k: Int, acc: Long): Long = if (k <= 1) acc else go(k - 1, acc * k)
    return go(n, 1L)
}

fun main() {
    var result = ""
    fun add(s: String) { result += s }
    add("O"); add("K")
    println(result)
    val prefix = "x="
    fun show(v: Int): String = prefix + v
    println(show(42))
    class Point(val x: Int, val y: Int) { fun sum() = x + y }
    data class P(val a: Int, val b: String)
    println(Point(2, 3).sum())
    println(P(1, "z"))
    object Single { val v = 7 }
    println(Single.v)
    println(fact(10))
    println(Counter(10).total(3))
    fun twice(f: (Int) -> Int, x: Int): Int = f(f(x))
    println(twice({ it * 3 }, 2))
}
