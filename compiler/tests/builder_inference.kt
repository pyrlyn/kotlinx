// Builder inference: the type argument of `build` comes from calls on the lambda's receiver.
class Buildee<T> {
    val items = ArrayList<T>()
    fun yield(x: T) { items.add(x) }
}
fun <F> build(block: Buildee<F>.() -> Unit): Buildee<F> = Buildee<F>().apply(block)

class Tag(val n: String)
fun <T> runWith(x: T, f: T.() -> Int): Int = with(x, f)

fun main() {
    val ints = build { yield(1); yield(2); yield(40) }
    var s = 0
    for (x in ints.items) s += x
    println(s)
    val strs = build { yield("a"); yield("b") }
    println(strs.items.size)
    val tags = build { yield(Tag("x")) }
    println(tags.items[0].n)
    println(runWith(Tag("abc")) { n.length })
    println(Tag("q").let { it.n })
}
