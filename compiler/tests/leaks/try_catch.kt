// Exceptions: references held in every frame between throw and catch are released while unwinding;
// exception objects with reference fields; catch variables; finally on normal, caught, rethrown, return
// and break paths; exceptions thrown from constructors and init blocks; Array(n) { } of objects; do/while.
class Node(val v: Int, val next: Node?)
class Fail(val payload: Node, msg: String) : RuntimeException(msg)
class Strict(val n: Node) {
    init { if (n.v % 7 == 0) throw IllegalStateException("bad " + n.v) }
}

fun build(k: Int): Node { var n: Node? = null; for (i in 0 until k) n = Node(i, n); return n!! }

fun level(k: Int, depth: Int): Int {
    val local = build(3)
    val s = "s" + k
    if (depth == 0) {
        if (k % 4 == 0) throw Fail(build(k % 5 + 1), "fail " + s)
        if (k % 4 == 1) throw IllegalArgumentException(s)
        return local.v + s.length
    }
    return level(k, depth - 1) + local.v
}

fun withFinally(k: Int, log: ArrayList<String>): Int {
    val held = build(2)
    try {
        if (k % 3 == 0) return held.v
        return level(k, 2)
    } finally {
        log.add("f" + k)
    }
}

fun main() {
    var total = 0L
    val log = ArrayList<String>()
    for (k in 0 until 400) {
        try {
            total += level(k, k % 4)
        } catch (e: Fail) {
            total += e.payload.v + e.message!!.length
        } catch (e: IllegalArgumentException) {
            total += 1
        }
        try { total += withFinally(k, log) } catch (e: RuntimeException) { total += 2 }
        val r = try { Strict(Node(k, build(2))).n.v } catch (e: IllegalStateException) { -1 }
        total += r
        try {
            try { level(k, 1) } finally { log.add("inner") }
        } catch (e: Exception) { total += 3 }
        for (j in 0 until 3) { try { if (j == 1) break; total += j } finally { log.add("b") } }
        try { val a = Array(2) { Node(it, null) }; total += a[k % 3].v } catch (e: IndexOutOfBoundsException) { total += 5 }
        try { try { throw Fail(build(2), "c") } catch (e: Fail) { val keep = build(1); throw IllegalStateException("re" + keep.v) } finally { log.add("rf") } } catch (e: IllegalStateException) { total += e.message!!.length }
        val arr = Array(3) { i -> Node(i + k, null) }
        total += arr[2].v
        var d = 0
        do { d++; val tmp = build(1); total += tmp.v } while (d < 2)
    }
    println(total)
    println(log.size)
}
