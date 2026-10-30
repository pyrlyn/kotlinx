// Function types with receiver (R.(A) -> X): lambdas with receiver, x.f(), implicit-receiver f().
class Buildee<T> { val items = ArrayList<T>(); fun add(x: T) { items.add(x) } }
fun <T> build(instructions: Buildee<T>.() -> Unit): Buildee<T> { val b = Buildee<T>(); b.instructions(); return b }
fun sb(f: StringBuilder.() -> Unit): String { val s = StringBuilder(); s.f(); return s.toString() }
fun calc(x: Int, f: Int.(Int) -> Int): Int = x.f(10)
fun <T> T.runExt(fn: T.() -> String): String = fn()
fun main() {
    println(sb { append("a"); append(1); this.append("!") })
    println(calc(5) { this * it })
    println(calc(5) { y -> this + y })
    println("x".runExt { this + length })
    val b = build<Int> { add(1); add(2) }
    println(b.items.size)
}
