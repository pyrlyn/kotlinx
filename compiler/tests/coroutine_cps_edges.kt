// CPS coroutine edge cases: suspend methods, generic suspend funs, exceptions through awaits, deep
// suspend recursion, suspension inside inlined forEach.
import kotlinx.coroutines.*
class Counter(var n: Int) {
    suspend fun bump(by: Int): Int { delay(1L); n += by; return n }
}
suspend fun <T> twice(x: T, log: ArrayList<String>): T { yield(); log.add("t:" + x); yield(); return x }
suspend fun boom(k: Int): Int { delay(2L); if (k > 1) throw IllegalArgumentException("boom " + k); return k }
suspend fun deep(d: Int): Int = if (d == 0) { yield(); 1 } else deep(d - 1) + 1
fun main() = runBlocking {
    val log = ArrayList<String>()
    val c = Counter(0)
    val j1 = launch { for (i in 1..3) log.add("a" + c.bump(1)) }
    val j2 = launch { for (i in 1..3) log.add("b" + c.bump(10)) }
    j1.join(); j2.join()
    println(log)
    val l2 = ArrayList<String>()
    println(twice("s", l2) + twice(5, l2))
    println(l2)
    try { println(boom(1)); println(boom(2)) } catch (e: IllegalArgumentException) { println("caught " + e.message) } finally { println("finally") }
    println("deep " + deep(50))
    val xs = ArrayList<Int>()
    listOf(3, 1, 2).forEach { delay(it.toLong()); xs.add(it) }
    println(xs)
    var acc = 0
    repeat(3) { acc += it }
    println("acc " + acc + " t " + currentTimeVirtual())
}
