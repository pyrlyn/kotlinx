fun run(block: () -> Unit) = "unit"
fun run(block: () -> String) = "str " + block()
fun <T> with(x: T, f: T.() -> Unit) = "mywith"
fun repeat(n: Int, f: (Int) -> Unit) = "myrep $n"

fun main() {
    println(run { println("not called") })
    println(run { "x" })
    println(with(1) { })
    println(repeat(2) { })
}
