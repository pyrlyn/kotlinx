import kotlinx.coroutines.*

// Regression tests for the coroutine scheduler: stale join registrations, timer order, async with a context.
fun main() = runBlocking {
    // A joiner that gave up (here: its withTimeout expired) must not be woken later by the job it was joining:
    // that stale wake-up cut its next delay short ("woke at 5" instead of "woke at 102").
    val target = launch { delay(5L) }
    val waiter = launch {
        try { withTimeout(2L) { target.join() } } catch (e: TimeoutCancellationException) { println("timed out at " + currentTimeVirtual()) }
        delay(100L)
        println("woke at " + currentTimeVirtual())
    }
    waiter.join()

    // Equal wake times keep FIFO order; earlier times first.
    val order = ArrayList<Int>()
    val js = ArrayList<Job>()
    for (i in 0 until 6) js.add(launch { delay(((i % 3) * 10).toLong()); order.add(i) })
    for (j in js) j.join()
    println(order)

    // Spurious duplicate wake-ups: cancelling twice while queued resumes the job once.
    var resumes = 0
    val twice = launch { try { delay(1000L) } catch (e: CancellationException) { resumes++ } }
    yield()
    twice.cancel(); twice.cancel()
    twice.join()
    println("resumes=" + resumes)

    // async takes a context like launch: a handler, and SupervisorJob() keeps the failure in the Deferred.
    val d = async(SupervisorJob()) { delay(1L); throw IllegalStateException("boom") }
    try { d.await() } catch (e: IllegalStateException) { println("await: " + e.message) }
    val ok = async(Dispatchers.Default) { 21 * 2 }
    println("ok=" + ok.await())
    println("done at " + currentTimeVirtual())
}
