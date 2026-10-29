fun twice(x: Int): Int = x * 2
fun add(a: Int, b: Int): Int = a + b
class P(val x: Int) {
    fun scaled(k: Int): Int = x * k
    fun show(): String = "P$x"
}
interface Shape { fun area(): Int }
class Sq(val s: Int) : Shape { override fun area(): Int = s * s }
object Counter { var n = 0; fun bump(): Int { n++; return n } }

fun apply(f: (Int) -> Int, v: Int): Int = f(v)

fun main() {
    val f = ::twice
    println(f(4))
    println(apply(::twice, 5))
    val g: (Int, Int) -> Int = ::add
    println(g(2, 3))
    val mk = ::P
    val p = mk(7)
    println(p.show())
    val sc = P::scaled
    println(sc(p, 3))
    val bound = p::scaled
    println(bound(10))
    val gx = P::x
    println(gx(P(9)))
    val xs = arrayListOf(1, 2, 3)
    println(xs.map(::twice))
    val ar = Shape::area
    println(ar(Sq(4)))
    val b2 = Counter::bump
    b2(); b2()
    println(Counter.n)
    val ps = arrayListOf(P(1), P(2))
    println(ps.map(P::show))
}
