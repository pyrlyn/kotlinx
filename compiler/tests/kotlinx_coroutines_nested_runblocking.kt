// runBlocking nested inside a coroutine waits for its own children on the shared event loop.
import kotlinx.coroutines.*

fun work(log: ArrayList<String>, tag: String) = runBlocking {
    launch { delay(10L); log.add(tag + ":a") }
    launch { delay(5L); log.add(tag + ":b") }
    log.add(tag + ":body")
}

fun main() {
    val log = ArrayList<String>()
    runBlocking {
        log.add("outer start")
        work(log, "n1")
        log.add("after n1")
        val j = launch { work(log, "n2") }
        j.join()
        runBlocking { runBlocking { delay(1L); log.add("deep") } }
        log.add("outer end")
    }
    work(log, "top")
    for (s in log) println(s)
    println("virtual time " + currentTimeVirtual())
}
