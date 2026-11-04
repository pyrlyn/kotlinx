// The common type of `if`/`when` branches and list elements types the alternatives that give no information on
// their own (`build {}`, a bare `Buildee()`), whatever their order and distance in the source.
class Target(val n: String)

class Buildee<TV> {
    var v: TV? = null
    fun set(x: TV) { v = x }
}

fun <PTV> build(instructions: Buildee<PTV>.() -> Unit): Buildee<PTV> = Buildee<PTV>().apply(instructions)

fun choose(k: Int) {
    // The informative branch comes last and far from the first one.
    val b = when (k) {
        0 -> build {}
        1 -> Buildee()
        2 -> {
            println("two")
            build {}
        }
        else -> build {
            set(Target("t" + k))
        }
    }
    println(k.toString() + " " + (b.v?.n ?: "-"))
}

fun main() {
    val flag = false
    val a = if (flag) build {} else build { set(Target("else")) }
    println(a.v?.n)
    for (k in 0 until 4) choose(k)
    val xs = listOf(build {}, Buildee(), build { set(Target("l")) })
    println(xs.size.toString() + " " + xs[2].v?.n)
    functionTyped()
}

// Elements of a function type (kt35914): not unified through a common type.
class InvF<T>
fun <T> barF(x: InvF<T>.() -> Unit) = x
fun functionTyped() { println(listOf(barF<Char> { }, barF { }).size) }
