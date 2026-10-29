interface Shape {
    fun area(): Int
    companion object {
        const val UNIT = 10
        fun square(s: Int): Shape = Sq(s)
    }
}
class Sq(val s: Int) : Shape { override fun area() = s * s * Shape.UNIT }
class Counter {
    companion {
        val start = 5
        fun make() = Counter()
    }
    var n: Int = start
}
fun main() {
    println(Shape.square(2).area())
    println(Shape.UNIT)
    val c = Counter.make(); c.n++
    println(c.n + Counter.start)
}
