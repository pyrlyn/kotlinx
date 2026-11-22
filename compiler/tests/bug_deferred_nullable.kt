import kotlinx.coroutines.*

fun main() = runBlocking {
    val a = async<String?> { null }
    println("null result: ${a.await()}")
    val b = async<String?> { "x" }
    println("value result: ${b.await()}")
    val d = async { 42 }
    println("plain int: ${d.await()}")
}
