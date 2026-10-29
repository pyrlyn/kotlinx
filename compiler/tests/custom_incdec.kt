class Counter(val n: Int) {
    operator fun inc(): Counter = Counter(n + 1)
    operator fun dec(): Counter = Counter(n - 1)
    override fun toString(): String = "C" + n
}

data class P(val x: Int)
operator fun P.inc(): P = P(x * 2)

class Holder { var c = Counter(10) }

fun main() {
    var c = Counter(0)
    c++
    ++c
    println(c)
    val old = c++
    println("$old $c")
    val nw = --c
    println("$nw $c")
    var p = P(3)
    p++
    println(p)
    val h = Holder()
    h.c++
    h.c--
    h.c++
    println(h.c)
    var i = 5
    i++
    println(i)
}
