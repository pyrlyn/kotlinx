// Regression: the owned receiver of a generic member call (`O.go(...)`) is released when the call throws.
interface KS<T> { fun d(x: P): T }
class P(val v: Int)
class X : KS<String> { override fun d(x: P): String { throw IllegalStateException("t") } }
class Y : KS<Int> { override fun d(x: P): Int = 1 }
object O { fun <T> go(i: KS<T>): T { val p = P(3); return i.d(p) } }
fun main() {
    println(O.go(Y()))
    try { O.go(X()) } catch (ex: IllegalStateException) { println(ex.message) }
}
