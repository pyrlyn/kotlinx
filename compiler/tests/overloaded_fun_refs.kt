fun bar(x: Int) { println("int $x") }
fun bar(s: String) { println("str $s") }
fun take(f: (String) -> Unit) = f("a")
fun <T> gen(e: T, f: (T) -> Unit) = f(e)
fun <T> genS(e: T, f: suspend (T) -> Unit) { println("suspend ok") }

fun main() {
    take(::bar)
    val g: (Int) -> Unit = ::bar
    g(3)
    gen(5, ::bar)
    gen("s", ::bar)
    genS(1, ::bar)
}
