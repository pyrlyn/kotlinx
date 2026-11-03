fun main() {
    val base = 10
    var calls = 0
    fun fact(n: Int): Int { calls++; return if (n <= 1) base else n * fact(n - 1) }
    fun <T> pair(x: T): String = "$x/$base"
    fun sum(vararg xs: Int): Int { var s = base; for (x in xs) s += x; calls++; return s }
    println(fact(4))
    println(pair("a") + pair(3))
    println(sum(1, 2, 3))
    println(calls)
}
