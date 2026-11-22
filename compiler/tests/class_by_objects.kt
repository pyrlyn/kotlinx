// Class delegation (`I by expr`) in object expressions, object declarations and value classes; a delegated member
// hides the superclass's implementation.

interface Shape {
    fun area(): Int
    val name: String
}
class Square(val side: Int) : Shape {
    override fun area() = side * side
    override val name: String get() = "square"
}

open class ShapeFactory {
    open fun make(): Shape = Square(2)
}
class Wrapper : ShapeFactory() {
    override fun make(): Shape = Square(5)
    // `super.make()` in the delegate is evaluated outside the object.
    fun wrapped(): Shape = object : Shape by super.make() {
        override val name: String get() = "wrapped"
    }
}

fun local(s: Shape): Shape = object : Shape by s {}

value class Named(val shape: Shape) : Shape by shape

object Unit2 : Shape by Square(1)

class WithCompanion {
    companion object : Shape by Square(6)
}

interface Labeled {
    var label: String
}
open class Stored : Labeled {
    override var label: String = "stored"
}
class Constant : Labeled {
    override var label: String
        get() = "delegated"
        set(_) {}
}
class Hidden : Stored(), Labeled by Constant()

fun main() {
    val w = Wrapper().wrapped()
    println(w.area().toString() + " " + w.name)
    val l = local(Square(3))
    println(l.area().toString() + " " + l.name)
    println(Named(Square(4)).area())
    println(Unit2.area().toString() + " " + Unit2.name)
    println(WithCompanion.area())
    val h = Hidden()
    h.label = "assigned"
    val asLabeled: Labeled = h
    println(h.label + " " + asLabeled.label)
}
