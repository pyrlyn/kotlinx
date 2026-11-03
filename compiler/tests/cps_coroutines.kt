// nkc: cps
// kotlinx.coroutines subset (Kotlinx.kt): launch/async/join, channels, flows on the single-threaded scheduler.
import kotlinx.coroutines.*
suspend fun work(n: Int): Int { delay(10L); return n * 2 }
fun main() = runBlocking {
    val j = launch { println("child start"); yield(); println("child end") }
    println("parent")
    j.join()
    println("joined " + j.isCompleted)
    val a = async { work(20) }
    val b = async { work(1) }
    println(a.await() + b.await())
    val ch = Channel<Int>(2)
    launch { for (i in 1..5) ch.send(i * i); ch.close() }
    var s = 0
    ch.consumeEach { s += it }
    println("sum " + s)
    val f = flow<Int> { for (i in 1..6) emit(i) }.filter { it % 2 == 0 }.map { it * 10 }
    f.collect { println(it) }
    println(f.toList())
    val c = launch { println("never") }
    c.cancel()
    coroutineScope { val x = async { 7 }; launch { println(x.await() + 1) } }
    println("end")
}
