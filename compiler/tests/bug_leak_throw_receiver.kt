// Regression: an owned temporary receiver leaked when its method call or lateinit read threw (found by --leak-check).
class A(val n: Int) {
    lateinit var s: String
    fun boom(): Int { throw RuntimeException("boom " + n) }
}
interface I { fun go(): Int }
class B : I { override fun go(): Int { throw IllegalStateException("go") } }
fun mk(): I = B()
fun main() {
    try { println(A(1).boom()) } catch (e: RuntimeException) { println(e.message) }
    try { println(A(2).s) } catch (e: RuntimeException) { println("lateinit") }
    try { println(mk().go()) } catch (e: IllegalStateException) { println(e.message) }
    for (i in 0 until 3) { try { A(i).boom() } catch (e: RuntimeException) { println(e.message) } }
}
