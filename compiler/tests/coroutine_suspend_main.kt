// suspend fun main: runs inside a generated runBlocking.
import kotlinx.coroutines.*

suspend fun twice(x: Int): Int { delay(5L); return x * 2 }

suspend fun main() {
    val a = twice(21)
    println("a " + a)
    var got = 0
    coroutineScope {
        launch { delay(3L); got += 1 }
        launch { delay(1L); got += 10 }
    }
    println("got " + got + " t " + currentTimeVirtual())
}
