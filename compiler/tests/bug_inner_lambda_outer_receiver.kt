// Regression: a lambda nested in a receiver lambda could not call members of that receiver.
class Sc(val k: Int) { fun hi(): Int = k; var hits = 0 }
class Src(val xs: ArrayList<Int>) { fun each(f: (Int) -> Unit) { for (x in xs) f(x) } }
fun run(block: Sc.() -> Unit) { with(Sc(10), block) }
fun inner(block: Sc.() -> Unit) { with(Sc(20), block) }

fun main() {
    val xs = ArrayList<Int>(); xs.add(1); xs.add(2)
    val src = Src(xs)
    val ev = ArrayList<String>()
    run { src.each { ev.add("r" + it + ":" + hi()); hits++ }; println("hits " + hits + " k " + k) }
    println(ev)
    // A nested receiver lambda's own receiver wins over the enclosing one.
    run { inner { println("inner k " + k + " hi " + hi()) }; println("outer k " + k) }
}
