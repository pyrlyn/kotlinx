// Interface properties: abstract vals implemented by stored properties (own or inherited) and by getters.
interface Shape { val name: String; val area: Int; fun describe(): String }
open class Sq(override val name: String, val side: Int) : Shape {
    override val area: Int get() = side * side
    override fun describe(): String = name + ":" + area
}
class Big(side: Int) : Sq("big", side)
fun show(s: Shape) = s.name + "=" + s.area
fun main() {
    val xs = arrayListOf<Shape>(Sq("a", 2), Big(5))
    for (s in xs) println(show(s) + " " + s.describe())
}
