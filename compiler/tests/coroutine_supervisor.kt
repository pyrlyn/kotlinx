import kotlinx.coroutines.*
fun main() = runBlocking {
    val handler = CoroutineExceptionHandler { _, e -> println("handled " + e.message) }
    supervisorScope {
        val a = launch(handler) { delay(10); throw IllegalStateException("child a") }
        val b = launch { delay(20); println("b survives") }
        val c = launch { throw IllegalArgumentException("child c, no handler") }
        joinAll(listOf(a, b, c))
        println("a done=" + a.isCompleted + " b done=" + b.isCompleted)
        val d = async { delay(5); throw IllegalStateException("async fail") }
        try { d.await() } catch (e: IllegalStateException) { println("await " + e.message) }
        println("scope alive")
    }
    try {
        supervisorScope {
            launch { delay(50); println("never") }
            throw IllegalStateException("body fails")
        }
    } catch (e: IllegalStateException) { println("body " + e.message) }
    try {
        coroutineScope {
            launch(handler) { throw IllegalStateException("regular scope") }
            launch { delay(10); println("never2") }
        }
    } catch (e: IllegalStateException) { println("caught " + e.message) }
    val job = launch(SupervisorJob() + handler) { throw IllegalStateException("detached") }
    job.join()
    println("done")
}
