import kotlinx.coroutines.*
fun main() {
    runBlocking {
        val handler = CoroutineExceptionHandler { _, e -> println("handled " + e.message) }
        val j = launch(SupervisorJob() + handler) { delay(100); throw IllegalStateException("late") }
        launch(SupervisorJob()) { delay(1000); println("never: not awaited") }
        println("body done")
        j.join()
        println("joined")
    }
    println(listOf(Unit, Unit))
    val l = mutableListOf<Unit>(); l.add(Unit); println(l.size)
    println("end")
}
