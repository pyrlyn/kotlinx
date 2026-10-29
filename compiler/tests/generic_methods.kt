class Box<T>(val value: T) {
    fun get(): T = value
    fun <R> pairWith(r: R): Pair2<T, R> = Pair2(value, r)
    fun <R> replace(r: R): Box<R> = Box(r)
}

class Pair2<A, B>(val first: A, val second: B)

class Util {
    var calls = 0
    fun <T> twice(x: T): ArrayList<T> {
        calls++
        val r = ArrayList<T>()
        r.add(x); r.add(x)
        return r
    }
    fun <T> pick(a: T, b: T, first: Boolean): T = if (first) a else b
    fun useTwice(): Int = twice(3).size + twice("s").size
}

fun <T> unbox(b: Box<T>): T = b.value

fun <A, B> firstOf(p: Pair2<A, B>): A = p.first

fun <T> sizeOf(xs: ArrayList<Box<T>>): Int = xs.size

fun main() {
    val b = Box(10)
    val p = b.pairWith("ten")
    println(p.second + p.first)
    val q = b.pairWith(2.5)
    println(q.second * 2)
    println(b.replace("r").get())
    println(unbox(Box(7)) + 1)
    println(unbox(Box("u")))
    println(firstOf(Pair2(5L, "x")) + 1L)
    val u = Util()
    println(u.twice(1L).size + u.twice(Box(1)).size)
    println(u.pick("a", "b", false))
    println(u.useTwice())
    println(u.calls)
    val xs = ArrayList<Box<Int>>()
    xs.add(Box(1))
    println(sizeOf(xs))
}
