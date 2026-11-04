// kotlinx.coroutines subset: Flow take/drop/onEach/transform/fold/count/first, receiveAsFlow, withTimeout(OrNull), withContext, Dispatchers, cancellation with finally.
import kotlinx.coroutines.*

suspend fun slow(ms: Long): String { delay(ms); return "done " + ms }

fun main() = runBlocking {
    val f = flow<Int> { for (i in 1..10) emit(i) }
    println(f.take(3).toList())
    println(f.drop(7).toList())
    println(f.onEach { if (it == 1) println("first seen") }.filter { it % 2 == 0 }.count())
    println(f.fold(0) { a, x -> a + x })
    println(f.first())
    val g: Flow<String> = f.transform { emit("a$it"); emit("b$it") }
    println(g.take(3).toList())
    println(withTimeoutOrNull(50L) { slow(10L) })
    println(withTimeoutOrNull(50L) { slow(100L) })
    try { withTimeout(5L) { slow(20L) } } catch (e: TimeoutCancellationException) { println("timeout: " + e.message) }
    val r = withContext(Dispatchers.IO) { slow(1L) + "!" }
    println(r)
    val j = launch(Dispatchers.Default) { delay(5L); println("launched on Default") }
    j.join()
    val ch = Channel<Int>(4)
    launch { for (i in 1..3) ch.send(i); ch.close() }
    println(ch.receiveAsFlow().map { it * 100 }.toList())
    try {
        coroutineScope {
            val c = launch { try { delay(1000L) } finally { println("child finally") } }
            delay(1L); c.cancel()
        }
    } catch (e: CancellationException) { println("cancelled") }
    println("end")
}
