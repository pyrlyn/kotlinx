import kotlin.coroutines.*
var saved: Continuation<Int>? = null
suspend fun waitValue(): Int = suspendCoroutine { c -> saved = c }
suspend fun immediate(): Int = suspendCoroutine { c -> c.resume(7) }
suspend fun bar() = 5
fun builder(c: suspend () -> Unit) { c.startCoroutine(Continuation(EmptyCoroutineContext) { it.getOrThrow(); println("completed") }) }
fun main() {
    var log = ""
    builder {
        log += "start;"
        val a = immediate()
        log += "imm " + a + ";"
        val b = waitValue()
        log += "got " + b + ";"
        val c = waitValue() + bar()
        log += "got2 " + c + ";"
    }
    println(log)
    saved!!.resume(10)
    println(log)
    saved!!.resume(20)
    println(log)
    val r: suspend () -> String = { "res" + bar() }
    r.startCoroutine(Continuation(EmptyCoroutineContext) { println("result " + it.getOrThrow()) })
    val bad: suspend () -> Int = { throw IllegalStateException("boom") }
    bad.startCoroutine(Continuation(EmptyCoroutineContext) { println("failure " + it.exceptionOrNull()?.message) })
    val withRecv: suspend String.() -> Unit = { println("recv " + this + bar()) }
    withRecv.startCoroutine("x", Continuation(EmptyCoroutineContext) { })
    lazyStart()
}

// createCoroutine: nothing runs until resume(Unit).
fun lazyStart() {
    val f: suspend () -> Int = { println("lazy body"); 42 }
    val c = f.createCoroutine(Continuation(EmptyCoroutineContext) { println("lazy " + it.getOrThrow()) })
    println("created")
    c.resume(Unit)
    saved = null
}
