// Anonymous objects: `object : T { }` expressions.
interface Shape { fun area(): Int; fun name(): String = "shape" }
abstract class Animal(val kind: String) { abstract fun sound(): String }

val unit = object : Shape { override fun area() = 1 }

fun square(n: Int): Shape = object : Shape {
    override fun area() = 49
    override fun name() = "square"
}

fun dog(): Animal = object : Animal("dog") { override fun sound() = "woof" }

class Holder {
    val s = object : Shape { override fun area() = 7 }
}

fun main() {
    println(unit.area())
    val sq = square(7)
    println(sq.name() + " " + sq.area())
    val d = dog()
    println(d.kind + " " + d.sound())
    println(Holder().s.area() + Holder().s.name().length)
    val counter = object {
        var c = 0
        fun bump(): Int { c++; return c }
    }
    counter.bump(); println(counter.bump())
    val shapes = listOf<Shape>(object : Shape { override fun area() = 2 }, object : Shape { override fun area() = 3 })
    var t = 0
    for (x in shapes) t += x.area()
    println(t)
}
