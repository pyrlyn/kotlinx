class Foo(val s: String? = null)
class Builder<T> {
    var resolver: ((Foo) -> T)? = null
    fun resolve(r: (Foo) -> T) { resolver = r }
}
fun <T> build(configure: Builder<T>.() -> Unit): Builder<T> = Builder<T>().apply(configure)

class Controller<E> { var tag = "" }
fun <E1> generate(block: Controller<E1>.() -> Unit): Controller<E1> = Controller<E1>().apply(block)
fun mark(c: Controller<String>) { c.tag = "marked" }

class Inv<T>(val n: Int)
fun <T> bar(x: Inv<T>.() -> Unit): Inv<T> = Inv<T>(1).apply(x)

class Sink<CT> { var got: CT? = null; fun yield(a: CT) { got = a } }
fun <FT> withArg(f: Sink<FT>.(Int) -> Unit): Sink<FT> { val s = Sink<FT>(); s.f(41); return s }

fun main() {
    val b = build { resolve { it.s } }
    println(b.resolver!!(Foo("x")))
    println(generate { mark(this) }.tag)
    val l = listOf(bar<Char> { }, bar { })
    println(l.size)
    println(withArg { yield(it + 1) }.got)
}
