// Nullable primitives (Int?, Long?, Double?): null, boxing, ?., ?:, !!, smart casts, null-aware ==, printing.
class C(val x: Int, var y: Long?)
fun f(b: Boolean): Int? = if (b) 5 else null
fun g(x: Int?): Int = x ?: -1
fun main() {
    val x: Int? = null
    println(x); println(f(true)); println(f(false)); println(g(f(true))); println(g(null))
    val c: C? = C(3, null)
    println(c?.x == 3); println(c?.x); val n: C? = null; println(n?.x); println(n?.x == 3)
    val y = f(true)
    if (y != null) println(y + 1)
    println(y!! * 2)
    c!!.y = 7L; println(c.y); println(c.y == 7L)
    val d: Double? = -0.0; println(d == 0.0)
    println("s=${f(false)} ${f(true)}")
    val l = ArrayList<Int?>(); l.add(1); l.add(null); println(l)
    more()
}
fun eq(a: Any?, b: Any?) = if (a is Double? && b is Int?) a == b else false
fun more() {
    println(eq(-0.0, 0)); println(eq(null, null)); println(eq(null, 0))
    val h: Int? = null; println(h.hashCode())
}
