// Function types (plain and with receiver) as supertypes of classes, objects and interfaces.
class Doubler : (Int) -> Int {
    override fun invoke(x: Int): Int = x * 2
}

class Greeter(val greeting: String) : String.() -> String {
    override fun invoke(s: String): String = "$greeting, $s"
}

object Adder : Int.(Int) -> Int {
    override fun invoke(a: Int, b: Int): Int = a + b
}

interface Transform : (String) -> String

class Upper : Transform {
    override fun invoke(s: String): String = s.uppercase()
}

open class Base(val tag: String)

class Tagged(tag: String) : Base(tag), (Int) -> String {
    override fun invoke(x: Int): String = "$tag$x"
}

fun apply(f: (Int) -> Int, x: Int) = f(x)
fun String.with(f: String.() -> String) = f()

fun main() {
    val d = Doubler()
    println(d(21))
    println(apply(d, 5))
    val g = Greeter("Hello")
    println("world".with(g))
    println(g("there"))
    val add: Int.(Int) -> Int = Adder
    println(3.add(4))
    println(Adder.invoke(1, 2))
    val t: Transform = Upper()
    println(t("abc"))
    println(listOf("x", "y").map(Upper()))
    println(Tagged("#")(7))
    val local = object : (Int) -> Boolean {
        override fun invoke(x: Int) = x > 0
    }
    println(listOf(-1, 2, 0, 5).filter(local))
    refs()
}

val inc: (Int) -> Int = { it + 1 }

class Holder {
    val op: (Int) -> Int get() = { it * 10 }
    fun twice(x: Int) = x * 2
}

class Ctx(val unit: String)

class Outer(val x: Int) {
    inner class In(val y: Int) { fun sum() = x + y }
}

val (Int.() -> String).shout: String get() = this(7) + "!"
val <T> T.self: T get() = this

fun withCtx(f: context(Ctx) Int.() -> String): String = with(Ctx("m")) { 2.f() }

val describe = context(c: Ctx) fun Int.(): String = "$this ${c.unit}"

fun refs() {
    println(inc(1))
    println(::inc.get()(2))
    val h = Holder()
    println(h.op(4))
    val t: Holder.(Int) -> Int = Holder::twice
    println(h.t(6))
    with(Ctx("kg")) { println(5.describe()) }
    println(withCtx(describe))
    val mk: Outer.(Int) -> Outer.In = Outer::In
    println(Outer(1).mk(2).sum())
    fun Int.loc(): String = "L$this"
    println((Int::loc::shout)())
    val s: String.() -> String = String::self
    println("me".s())
}
