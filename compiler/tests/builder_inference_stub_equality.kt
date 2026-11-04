// Two builder type variables linked through a receiver call (`emit2(get())` with `get(): K1` into a `K2`
// parameter): K2 is inferred as K1. `null as T` with T a primitive throws NullPointerException at run time.
class Foo<K11, K22>(val key: K11) {
    var count = 0
    fun emit1(key: K11) { count++ }
    fun get(): K11 = null as K11
    fun emit2(key: K22) { count++ }
}

fun <K1, K2> build(builder: Foo<K1, K2>.() -> Unit) { println("built") }

class Box<T> { fun get(): T = null as T }

fun main() {
    val x = 1
    build {
        emit1(x)
        emit2(get())
    }
    try {
        val v: Int = Box<Int>().get()
        println(v)
    } catch (e: NullPointerException) {
        println("npe")
    }
}
