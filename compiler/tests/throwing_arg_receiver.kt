// An owned receiver (object / companion) is released when a later argument throws.
class R(val v: Int) { companion object { fun mk(x: Int): R = R(x); fun <T> gen(x: T): T = x } }
object O { fun mk(x: Int): R = R(x) }
fun g(): Int { if (5 > 0) throw IllegalStateException("boom"); return 1 }
fun main() {
    try { println(R.mk(g()).v) } catch (e: Throwable) { println("c1 " + e.message) }
    try { println(O.mk(g()).v) } catch (e: Throwable) { println("c2 " + e.message) }
    try { println(R.gen(g())) } catch (e: Throwable) { println("c3 " + e.message) }
    var res: Result<Int>? = null
    try { res = Result.success(g()) } catch (e: Throwable) { res = Result.failure<Int>(e) }
    println(res!!.exceptionOrNull()?.message)
}
