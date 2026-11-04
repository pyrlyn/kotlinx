// Lambdas and coroutines capturing collections and their owners; List<Job>.joinAll / List<Deferred>.awaitAll.
import kotlinx.coroutines.*

class Holder(val name: String) {
    var cb: (() -> String)? = null
    val items = ArrayList<Holder>()
}

suspend fun collect(xs: List<String>): Int {
    yield()
    return xs.size
}

fun main() {
    for (i in 0 until 3) {
        val h = Holder("h")
        h.cb = { h.name + h.items.size }
        println(h.cb!!())
        h.items.add(h)
    }
    runBlocking {
        val acc = ArrayList<String>()
        val jobs = ArrayList<Job>()
        for (i in 0 until 3) jobs.add(launch { acc.add("j" + i); yield(); acc.add("k" + i) })
        jobs.joinAll(); println(listOf(async { 1 }, async { 2 }).awaitAll())
        val d = async { collect(acc) }
        println(acc.size.toString() + " " + d.await())
        val lst = arrayListOf("x", "y")
        val f: suspend () -> Int = { yield(); lst.size }
        println(f())
    }
}
