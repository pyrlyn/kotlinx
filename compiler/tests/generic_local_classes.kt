// Anonymous objects and local classes in generic functions that use the function's type parameters.
interface IFace<T, R> { fun call(x: T): R }
abstract class Recv<T> { abstract fun receive(x: T): String }
value class IC<T : Any>(val value: T)

fun <T, R> bar(v: T, f: IFace<T, R>): R = f.call(v)

fun <T : Any> underlying(a: IC<T>): T = bar(a, object : IFace<IC<T>, T> {
    override fun call(x: IC<T>): T = x.value
})

fun <T> receiver(tag: String): Recv<T> = object : Recv<T>() {
    override fun receive(x: T): String = tag + x
}

fun <T> boxed(x: T): String {
    class Holder(val v: T) { fun show() = "<$v>" }
    return Holder(x).show()
}

fun main() {
    println(underlying(IC(40)) + 2)
    println(underlying(IC("s")))
    println(receiver<Int>("n").receive(1))
    println(receiver<String>("s").receive("x"))
    println(boxed(3) + boxed("a"))
}
