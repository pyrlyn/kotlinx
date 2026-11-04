// Unchecked casts between Continuation instances (`it as Continuation<Any>`).
import kotlin.coroutines.*
fun builder(c: suspend () -> Unit) { c.startCoroutine(Continuation(EmptyCoroutineContext) { it.getOrThrow() }) }
class Box(val s: String)
var c: Continuation<Any>? = null
suspend fun <T> suspendMe(): T = suspendCoroutine { c = it as Continuation<Any> }
fun main() {
    var r = ""
    builder { val s: String = suspendMe(); r += s }
    c?.resume("str;")
    builder { val n: Int = suspendMe(); r += n }
    c?.resume(42)
    builder { val b: Box = suspendMe(); r += ";" + b.s }
    c?.resume(Box("box"))
    builder { try { val s: String = suspendMe(); r += s } catch (e: IllegalStateException) { r += ";caught " + e.message } }
    c?.resumeWithException(IllegalStateException("x"))
    c = null
    println(r)
}
