interface Shape { val name: String; fun area(): Int; fun describe(): String = "shape " + name }
class Sq(val s: Int) : Shape { override val name = "sq"; override fun area() = s * s }
class Loud(inner: Shape) : Shape by inner { override fun describe() = "LOUD " + name + " " + area() }
interface Box<T> { fun get(): T; fun put(x: T): Box<T> }
class IntBox(val v: Int) : Box<Int> { override fun get() = v; override fun put(x: Int): Box<Int> = IntBox(x) }
class Wrap(b: Box<Int>) : Box<Int> by b
fun main() {
    val l = Loud(Sq(3))
    println(l.area()); println(l.name); println(l.describe())
    val s: Shape = Loud(Sq(2)); println(s.area())
    val w = Wrap(IntBox(5)); println(w.get()); println(w.put(7).get())
}
