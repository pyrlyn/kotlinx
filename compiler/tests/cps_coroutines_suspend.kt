// nkc: cps
// Real suspension (stackful coroutines): yield interleaves, delay orders by virtual time,
// channels block senders/receivers, join/await suspend mid-body.
import kotlinx.coroutines.*

suspend fun worker(name: String, n: Int, log: ArrayList<String>) {
    for (i in 1..n) { log.add(name + i); yield() }
}

fun main() = runBlocking {
    val log = ArrayList<String>()
    val a = launch { worker("a", 3, log) }
    val b = launch { worker("b", 3, log) }
    a.join(); b.join()
    println(log)

    val order = ArrayList<String>()
    launch { delay(300L); order.add("slow") }
    launch { delay(100L); order.add("fast") }
    launch { delay(200L); order.add("mid") }
    delay(1000L)
    println(order)
    println(currentTimeVirtual())

    val ping = Channel<Int>(0)
    val pong = Channel<Int>(0)
    launch {
        for (i in 1..3) { val x = ping.receive(); println("pong " + x); pong.send(x * 10) }
    }
    for (i in 1..3) { ping.send(i); println("ping got " + pong.receive()) }

    val ch = Channel<Int>(2)
    val events = ArrayList<String>()
    launch { for (i in 1..5) { ch.send(i); events.add("s" + i) }; ch.close() }
    val consumer = launch { ch.consumeEach { events.add("r" + it); yield() } }
    consumer.join()
    println(events)

    val d = async { delay(50L); 21 }
    println("before await")
    println(d.await() * 2)
    coroutineScope {
        launch { delay(10L); println("inner child") }
        println("inner body")
    }
    println("after scope")
}
