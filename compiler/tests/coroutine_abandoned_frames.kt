// Coroutines left suspended when runBlocking cannot make progress are cancelled and unwound, so the objects
// their frames hold are released (checked by --leak-check) before the deadlock is reported.
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*

class Box(val n: Int)

fun main() {
    val log = ArrayList<String>()
    try {
        runBlocking {
            val ch = Channel<Box>(0)
            launch {
                val held = Box(1)
                try { val b = ch.receive(); log.add("never " + b.n) } finally { log.add("receiver unwound " + held.n) }
            }
            launch {
                val held = Box(2)
                val other = Channel<Int>(0)
                try { other.send(held.n) } finally { log.add("sender unwound " + held.n) }
            }
            val mine = Box(3)
            ch.receive()
            log.add("never " + mine.n)
        }
    } catch (e: IllegalStateException) {
        log.add("caught: " + e.message)
    }
    for (s in log) println(s)
}
