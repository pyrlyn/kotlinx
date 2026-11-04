// Cancellation of started coroutines: suspension points throw CancellationException,
// isActive turns false, finally blocks run, children are cancelled with the parent.
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*

fun main() = runBlocking {
    val log = ArrayList<String>()
    val j = launch {
        try {
            var i = 0
            while (true) { log.add("tick" + i); i++; delay(10L) }
        } finally {
            log.add("cleanup")
        }
    }
    delay(25L)
    j.cancel()
    j.join()
    println(log)
    println("" + j.isCancelled + " " + j.isCompleted + " " + j.isActive)

    // Cooperative loop with isActive.
    var spins = 0
    val k = launch { while (isActive) { spins++; yield() } }
    yield(); yield(); yield()
    k.cancel(); k.join()
    println("spins>0: " + (spins > 0))

    // Catching the exception at the suspension point.
    val caught = ArrayList<String>()
    val w = launch {
        try { delay(1000L) } catch (e: CancellationException) { caught.add("caught: " + e.message) }
    }
    yield(); w.cancel(); w.join()
    println(caught)

    // Cancelling a parent cancels its children; a receiver blocked on a channel is woken.
    val ch = Channel<Int>(0)
    val events = ArrayList<String>()
    val parent = launch {
        launch { try { ch.receive(); events.add("got") } finally { events.add("child done") } }
        try { delay(500L) } finally { events.add("parent done") }
    }
    delay(5L)
    parent.cancel(); parent.join()
    println(events)

    // Deferred cancelled before await.
    val d = async { delay(100L); 42 }
    yield(); d.cancel()
    try { println(d.await()) } catch (e: CancellationException) { println("await: " + e.message) }
    println("virtual time " + currentTimeVirtual())
}
