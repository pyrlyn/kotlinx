// Regressions: a safe call on a call result keeps the owned result (was leaked); `a as T` from Any with T bound to
// String or a primitive in a generic instance (was "nullable cast ... not supported").
class E(val m: String?)
class W(val e: E?) { fun get(): E? = e }
fun mk(): E? = E("x" + 1)

fun <T> cast(a: Any?): T = a as T

fun main() {
    val w = W(E("m" + 2))
    println(w.get()?.m)
    println(mk()?.m)
    println(mk()?.m?.length)
    println(cast<String>("s"))
    println(cast<Int>(41) + 1)
    println(cast<String?>(null))
    try { cast<Int>("no") } catch (e: ClassCastException) { println("cce") }
}
