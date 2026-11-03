// Regression: owned arguments already evaluated are released when a later argument throws.
class A(val s: String, val n: Int)
class B(val a: A, val t: String) {
    fun m(x: String, y: Int): Int = x.length + y
}
fun boom(): Int { throw IllegalStateException("boom") }
fun f(x: String, y: A, z: Int): Int = x.length + y.n + z
fun safe(): Int = try { boom() } catch (e: IllegalStateException) { 7 }
fun apply2(x: String, g: (Int) -> Int): Int = g(x.length)
fun main() {
    try { A("a" + 1, boom()) } catch (e: IllegalStateException) { println("ctor " + e.message) }
    try { f("x" + 2, A("q" + 3, 1), boom()) } catch (e: IllegalStateException) { println("fun " + e.message) }
    val b = B(A("k", 1), "t")
    try { b.m("m" + 4, boom()) } catch (e: IllegalStateException) { println("method " + e.message) }
    try { B(A("n" + 5, 2), "u" + boom()) } catch (e: IllegalStateException) { println("nested " + e.message) }
    println(f("ok" + 6, A("w" + 7, 2), safe()))
    println(apply2("ab" + 8) { if (it > 100) boom() else it * 2 })
}
