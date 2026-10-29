@JvmInline
value class Meters(val v: Double) {
    operator fun plus(o: Meters): Meters = Meters(v + o.v)
    fun twice(): Meters = Meters(v * 2)
}

value class Name(val s: String)

value class Wrap<T>(val x: T)

interface Shape { fun area(): Int }
value class Square(val side: Int) : Shape {
    override fun area(): Int = side * side
}

fun total(xs: ArrayList<Meters>): Meters {
    var t = Meters(0.0)
    for (m in xs) t += m
    return t
}

fun main() {
    val a = Meters(1.5)
    println((a + a.twice()).v)
    println(a == Meters(1.5))
    println(a != Meters(2.0))
    println(Name("x" + 1) == Name("x1"))
    println(Name("bob"))
    println("m=$a")
    val w = Wrap(5)
    println(w == Wrap(5))
    println(Wrap("s"))
    val sh: Shape = Square(4)
    println(sh.area())
    val xs = ArrayList<Meters>()
    xs.add(Meters(1.0)); xs.add(Meters(2.0))
    println(total(xs).v)
}
