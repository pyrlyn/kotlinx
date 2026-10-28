// A user function named like a built-in (check) shadows it; when/if branches yielding different
// classes that share one interface produce that interface (checked against Kotlin Native).
interface Obj { fun v(): Long }
class A(val a: Long) : Obj { override fun v(): Long = a }
class B(val a: Long, val b: Long) : Obj { override fun v(): Long = a + b }

fun check(x: Long): Long = x * 2

fun mk(i: Long): Obj = when (i % 2L) {
    0L -> A(i)
    else -> B(i, 1L)
}

fun pick(f: Boolean): Obj = if (f) A(5L) else B(1L, 2L)

fun main() {
    println(check(21L))
    var s = 0L
    for (i in 0 until 5) s += mk(i.toLong()).v()
    println(s)
    println(pick(true).v() + pick(false).v())
}
