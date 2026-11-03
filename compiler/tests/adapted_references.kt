fun join(x: String, vararg ys: String): String = x + ys.size
fun greet(name: String = "kx", punct: String = "!") = "hi " + name + punct
fun count(x: Int): Int { println("count $x"); return x + 1 }

fun call1(f: (String) -> String, x: String) = f(x)
fun call0(f: () -> String) = f()
fun runUnit(f: (Int) -> Unit) = f(7)

fun main() {
    println(call1(::join, "a"))
    println(call0(::greet))
    println(call1(::greet, "you"))
    runUnit(::count)
}
