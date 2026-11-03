class Box<T> {
    var items = 0
}
class Target(val n: Int)

fun <T> build(block: Box<T>.() -> Unit): Box<T> { val b = Box<T>(); b.block(); return b }
fun <T> each(f: (T) -> Unit) {}
fun takesBox(b: Box<Target>) { b.items += 1 }
fun takesTarget(t: Target) { println(t.n) }

fun main() {
    val b = build {
        val mine: Box<Target> = this
        takesBox(mine)
    }
    println(b.items)
    each { takesTarget(it) }
    println("done")
}
