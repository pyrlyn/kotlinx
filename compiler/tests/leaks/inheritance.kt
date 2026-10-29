// Leak test: class inheritance (open/abstract classes, overrides, super constructor calls, virtual dispatch).
interface Named { fun name(): String }
abstract class Shape(val id: Int) : Named {
    var tag = "s" + id
    abstract fun area(): Int
    open fun describe(): String = name() + "#" + id + "=" + area()
    override fun name(): String = "shape"
}
open class Rect(id: Int, val w: Int, val h: Int) : Shape(id) {
    override fun area(): Int = w * h
    override fun name(): String = "rect"
}
class Square(id: Int, s: Int) : Rect(id, s, s) {
    var next: Shape? = null
    override fun name(): String = "square"
    override fun describe(): String = "[" + tag + "]"
}
class Circle(id: Int, val r: Int) : Shape(id) {
    override fun area(): Int = 3 * r * r
}
fun total(xs: ArrayList<Shape>): Int { var s = 0; for (x in xs) s += x.area(); return s }
fun pick(i: Int): Shape = if (i % 3 == 0) Rect(i, i, 2) else if (i % 3 == 1) Square(i, i) else Circle(i, i)
fun main() {
    var acc = 0
    for (r in 0 until 50) {
        val xs = ArrayList<Shape>()
        for (i in 0 until 9) xs.add(pick(i))
        acc += total(xs)
        val sq = Square(r, 2)
        sq.next = sq
        sq.next = null
    }
    println(acc)
    val ys = ArrayList<Named>()
    ys.add(Rect(1, 2, 3)); ys.add(Square(2, 4)); ys.add(Circle(3, 1))
    for (y in ys) println(y.name())
    println(pick(0).describe() + " " + pick(4).describe() + " " + pick(5).describe())
    val s: Shape = Square(7, 3)
    println(s.tag + " " + s.area() + " " + s.id)
}
