class MyErr(val code: Int) : Exception("code " + code)
class Custom(val extra: String) : Throwable("msg", null)
fun main() {
    try { throw MyErr(3) } catch (e: Exception) { println(e.message) }
    val c = Custom("x"); println(c.extra + " " + c.message + " " + c.cause)
    try { throw Throwable("t") } catch (e: Throwable) { println(e.message + " " + (e.cause == null)) }
    val w = RuntimeException("w"); println(w.cause == null)
}
