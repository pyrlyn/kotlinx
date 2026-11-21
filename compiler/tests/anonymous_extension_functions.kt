// Anonymous extension functions (`fun R.() = ...`), labels on them, and extension properties of function type.
val top = fun Int.(n: Int): Int = this * n
val nullableRecv = fun String?.(): Int = this?.length ?: -1

fun appendTo(suffix: String) = fun String.(): String = this + suffix

fun <T> show() = fun (t: T) = "<$t>"

class Box(val v: Int) {
    val plus: Int.(Int) -> Int get() = fun Int.(k: Int) = this + k + v
    fun scaled() = fun Int.(): Int = this * v
}

val Int.twice: (Int) -> Int get() = { x -> x * this * 2 }
val ((Int) -> Int).doubled: (Int) -> Int get() = { x -> this(x) * 2 }

fun String.tagged(): String {
    val f = fun Int.(): String = "${this@tagged}:$this"
    return 1.f()
}

fun main() {
    println(3.top(4))
    println(top(2, 5))
    println(null.nullableRecv())
    println("abc".nullableRecv())
    val bang = appendTo("!")
    println("hi".bang())
    println(show<String>()("s"))
    val b = Box(10)
    println(1.(b.plus)(2))
    println(b.plus(1, 2))
    println(5.(b.scaled())())
    println(3.twice(4))
    val inc = { x: Int -> x + 1 }
    println(inc.doubled(4))
    println("t".tagged())
    val l = l@ fun (x: Int): Int { if (x < 0) return@l 0; return x }
    println(l(-3))
    println(l(3))
    val m = (m@ fun Int.() = this + 1)
    println(9.m())
    println(1.(fun Int.(i: Int) = i + this)(2))
    val s: String? = "q"
    val f: String.(Int) -> String = { n -> this.repeat(n) }
    println(s?.f(3))
    val n: String? = null
    println(n?.f(3))
    more()
}

// A receiver lambda in a method: its own `this` and the enclosing class's members side by side.
class Scale(val k: Int) {
    fun by(): Int.() -> Int = { this * k }
}

fun more() {
    println(5.(Scale(3).by())())
}
