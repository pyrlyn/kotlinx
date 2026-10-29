interface Source<T> {
    fun next(): T
    fun name(): String
}

class Counter(var n: Int) : Source<Int> {
    override fun next(): Int { n++; return n }
    override fun name(): String = "counter"
}

class Repeat<T>(val v: T) : Source<T> {
    override fun next(): T = v
    override fun name(): String = "repeat"
}

abstract class Base<T>(val tag: T) {
    abstract fun show(): String
    fun tagged(): T = tag
}

class Derived(val x: Int) : Base<String>("d") {
    override fun show(): String = tag + x
}

interface Mapper<A, B> { fun map(a: A): B }
object Len : Mapper<String, Int> { override fun map(a: String): Int = a.length }

fun <T> take2(s: Source<T>): ArrayList<T> {
    val r = ArrayList<T>()
    r.add(s.next()); r.add(s.next())
    return r
}

fun sum(s: Source<Int>, k: Int): Int { var t = 0; for (i in 0 until k) t += s.next(); return t }

fun main() {
    val c = Counter(0)
    println(sum(c, 3))
    val src: Source<Int> = Counter(10)
    println(src.next() + src.name().length)
    val r = Repeat("hi")
    println(take2(r)[1] + r.name())
    println(take2(Counter(5))[1])
    val d = Derived(7)
    println(d.show() + d.tagged())
    val b: Base<String> = d
    println(b.show())
    println(Len.map("four"))
    val ms = ArrayList<Source<Int>>()
    ms.add(Counter(1)); ms.add(Repeat(9))
    var t = 0
    for (m in ms) t += m.next()
    println(t)
}
