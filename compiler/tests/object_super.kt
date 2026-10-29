abstract class Shape(val name: String) {
    abstract fun area(): Int
    open fun describe(): String = "$name:${area()}"
}

open class Counter(start: Int) {
    var n = start
    fun inc(): Int { n += 1; return n }
}

object Unit3 : Shape("unit") { override fun area(): Int = 1 }

object Big : Shape("big"), Comparable<Int> {
    override fun area(): Int = 100
    override fun describe(): String = "BIG " + name
    override fun compareTo(other: Int): Int = area() - other
}

object Ticks : Counter(10)

fun show(s: Shape) = println(s.describe())

fun main() {
    show(Unit3)
    show(Big)
    println(Big.compareTo(50))
    println(Ticks.inc())
    println(Ticks.inc())
    println(Ticks.n)
}
