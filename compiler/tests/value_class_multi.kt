// Multi-field value classes: structural equality, hashCode and toString.
value class Point(val x: Int, val y: Int) {
    fun plus(o: Point) = Point(x + o.x, y + o.y)
}
value class Named(val name: String, val p: Point)

fun main() {
    val a = Point(1, 2); val b = Point(1, 2)
    println("${a == b} ${a.hashCode() == b.hashCode()} ${a != Point(2, 1)}")
    println(a.plus(b))
    println(Named("n", a))
    println(Named("n", a) == Named("n", Point(1, 2)))
}
