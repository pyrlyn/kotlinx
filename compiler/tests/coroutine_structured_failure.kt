// Structured concurrency: a failing child cancels its siblings and the parent's body; the failure is
// rethrown by runBlocking / coroutineScope / await, with finally blocks of every cancelled coroutine run.
import kotlinx.coroutines.*

fun main() {
    val log = ArrayList<String>()
    try {
        runBlocking {
            launch { try { delay(100L); log.add("sibling finished") } finally { log.add("sibling cancelled") } }
            launch { delay(10L); throw IllegalArgumentException("child failed") }
            try { delay(1000L); log.add("parent finished") } finally { log.add("parent body cancelled") }
        }
    } catch (e: IllegalArgumentException) {
        log.add("runBlocking threw: " + e.message)
    }
    runBlocking {
        try {
            coroutineScope {
                launch { try { delay(50L) } finally { log.add("scope sibling cancelled") } }
                launch { delay(5L); throw IllegalStateException("scope child failed") }
                delay(500L)
                log.add("scope body finished")
            }
        } catch (e: IllegalStateException) {
            log.add("coroutineScope threw: " + e.message)
        }
        // A failure in the body itself also cancels the children.
        try {
            coroutineScope {
                launch { try { delay(50L) } finally { log.add("child of failing body cancelled") } }
                yield()
                throw UnsupportedOperationException("body failed")
            }
        } catch (e: UnsupportedOperationException) {
            log.add("coroutineScope threw: " + e.message)
        }
        try {
            coroutineScope {
                val d = async<Int> { delay(5L); throw ArithmeticException("async failed") }
                println("await -> " + d.await())
            }
        } catch (e: ArithmeticException) {
            log.add("await threw: " + e.message)
        }
        log.add("outer still running")
    }
    for (s in log) println(s)
}
