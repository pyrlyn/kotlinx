interface Shape { fun name(): String }
class Square : Shape { override fun name() = "square"; fun side() = 2 }
interface Box<T> { fun get(): String }

fun <P> make(f: Box<P>.() -> Unit): Box<P> = (object : Box<P> { override fun get() = "anon" }).apply(f)

fun main() {
    val describe: (Shape) -> String = { it.name() }
    val onSquare: (Square) -> String = describe
    println(onSquare(Square()))
    val r: Shape.() -> Unit = { println("recv " + name()) }
    Square().apply(r)
    val b = make<Int> { println("in " + get()) }
    println(b.get())
}
