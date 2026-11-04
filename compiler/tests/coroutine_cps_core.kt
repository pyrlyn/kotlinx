// CPS coroutines: suspend funs and suspend lambdas are LLVM coroutines driven by the raw primitives.
class Box(val n: Int)
suspend fun tick(name: String, n: Int, log: ArrayList<String>): Int {
    var sum = 0
    for (i in 1..n) {
        val b = Box(i)
        log.add(name + i)
        kxCoSuspend()
        sum += b.n
    }
    return sum
}
suspend fun outer(log: ArrayList<String>): Unit {
    val a = tick("a", 3, log)
    log.add("a=" + a)
    val run: suspend (Int) -> String = { k -> kxCoSuspend(); "lam" + k + tick("c", 1, log) }
    log.add(run(5))
}
class Holder(val f: suspend () -> Unit)
fun main() {
    val log = ArrayList<String>()
    val h1 = Holder { outer(log) }
    val h2 = Holder { log.add("b-start"); tick("b", 2, log); log.add("b-end") }
    val c1 = kxCoStart(h1.f); val c2 = kxCoStart(h2.f)
    var d1 = false; var d2 = false
    while (!d1 || !d2) {
        if (!d1) d1 = kxCoResume(c1)
        if (!d2) d2 = kxCoResume(c2)
    }
    kxCoDestroy(c1); kxCoDestroy(c2)
    println(log)
}
