// Leaks on unusual exits (found by upstream under --leak-check): an inner instance made in its outer's
// constructor, a finally block that throws over a pending result, a finally block that returns over a pending
// exception, and a return inside a call's arguments.
class Outer(val name: String) {
    inner class Inner { val outer: Outer get() = this@Outer }
    val x: Inner = Inner()
}

class Box(var v: String)

fun throwsInFinally(b: Box): String {
    try {
        try { b.v += " try"; return b.v + "!" } finally { b.v += " f1" }
    } finally { b.v += " f2"; throw RuntimeException("boom") }
}

fun returnsInFinally(): Int {
    try { throw IllegalStateException("dropped") } finally { return 1 }
}

fun take(a: Any, b: Any) {}
fun returnInArgs(flag: Boolean): String {
    var q = "none"
    take(if (flag) { q = "args"; Box("x") } else "", return q)
}

fun main() {
    for (i in 0 until 2) { val o = Outer("o$i"); println(o.x.outer.name) }
    val b = Box("start")
    try { throwsInFinally(b) } catch (e: RuntimeException) { println(e.message + ":" + b.v) }
    println(returnsInFinally())
    println(returnInArgs(true))
    extra()
}

// An owned `when` subject, a delegate to `this`, and an owned collection iterated by a loop the body returns from.
fun String.len() = length
fun subject(s: String?) = when (s?.len()) { 0 -> "zero"; 2 -> "two"; else -> "other" }
class Self { operator fun getValue(thisRef: Any?, property: Any?) = if (thisRef == this) "self" else "other"; val s: String by this }
fun firstOver(n: Int): Int { for (x in arrayListOf(1, 5, 9)) if (x > n) return x; return -1 }
fun extra() { println(subject("ab") + " " + subject(null)); println(Self().s); println(firstOver(4)) }
