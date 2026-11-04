// Regression: an inner lambda inside a receiver lambda captured a variable of the
// enclosing lambda through `$outer`, which resolved to the shadowing receiver.
class Sc { fun hi(): Int = 1 }
class Src(val xs: ArrayList<Int>) { fun each(f: (Int) -> Unit) { for (x in xs) f(x) } }
fun run(block: Sc.() -> Unit) { with(Sc(), block) }

fun main() {
    val xs = ArrayList<Int>(); xs.add(1); xs.add(2)
    val src = Src(xs)
    val ev = ArrayList<String>()
    run { val k = hi(); src.each { ev.add("r" + it + k) } }
    println(ev)
    var n = 0
    run { src.each { n += it }; n += hi() }
    println(n)
}
