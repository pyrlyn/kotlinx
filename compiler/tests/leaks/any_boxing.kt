class P(val x: Int) { override fun toString(): String = "P($x)" }
data class D(val a: Int, val s: String)
class Q(val n: Int) {
    override fun equals(other: Any?): Boolean = other is Q && other.n == n
}
enum class Color { RED, GREEN }

fun describe(x: Any): String = when (x) {
    is Int -> "int " + (x + 1)
    is String -> "str " + x.length
    is P -> "p " + x.x
    else -> "other " + x
}

fun id(x: Any?): Any? = x

fun main() {
    val a: Any = 42
    val b: Any = "hi"
    val c: Any = P(3)
    println(a)
    println(b)
    println(c)
    println(describe(a) + " " + describe(b) + " " + describe(c) + " " + describe(2.5) + " " + describe(true))
    println(a == 42)
    println(b == "hi")
    println(a == b)
    println(D(1, "x") as Any == D(1, "x"))
    println(Q(5) as Any == Q(5))
    val n: Any? = null
    println(n == null)
    println(id(7))
    println(id(null))
    val i = a as Int
    println(i * 2)
    val s = b as String
    println(s + "!")
    println("t: $a $b $c ${Color.GREEN as Any}")
    println(a.toString() + c.toString())
    val xs = ArrayList<Any>()
    xs.add(1); xs.add("two"); xs.add(P(3)); xs.add('c')
    for (x in xs) println(describe(x))
    println(a is Int)
    println(a is String)
    println(c is P)
    eqs()
    more()
}

fun eqs() {
    println(Q(5) == Q(5))
    println(Q(5) != Q(6))
}

value class V(val value: String)
data class N(val b: V?)
class Acc(var n: Int)
var calls = 0
fun acc(a: Acc): Acc { calls++; return a }

fun more() {
    val v: Any = V("v")
    println(v)
    println(N(V("q")) as Any)
    println(N(null))
    val x: Any = 1.5
    println(x is Number)
    println((0.0 as Any) == (-0.0 as Any))
    println((-0.0).compareTo(0.0))
    println((7 as Any).hashCode() + ("ab" as Any).hashCode())
    val a = Acc(1)
    acc(a).n += 2
    acc(a).n++
    println("${a.n} $calls")
}
