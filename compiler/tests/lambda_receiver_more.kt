// Lambdas with receiver: typed parameters, `r.f(x)` calls of local function values, and builder inference
// from properties of the generic receiver.
class Point(val x: Int, val y: Int)

fun apply(arg: Point, f: Point.(scalar: Int) -> Point): Point = arg.f(2)

class Scope<K, V>(val key: K) {
    val got = ArrayList<String>()
    fun emit(v: V) { got.add("$key=$v") }
}

fun <K, V> scoped(key: K, binder: Scope<K, V>.() -> Unit): List<String> {
    val s = Scope<K, V>(key)
    s.binder()
    return s.got
}

fun main() {
    val p = apply(Point(3, 5), { scalar: Int -> Point(x * scalar, y * scalar) })
    println("${p.x} ${p.y}")
    val f: Int.(String) -> String = { y: String -> y + this }
    println(f(1, "a"))
    println(2.f("b"))
    println(f(3, "c"))
    println(scoped("k") { emit(key) })
}
