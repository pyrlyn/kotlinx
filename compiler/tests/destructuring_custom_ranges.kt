// Position-based destructuring over a user `rangeTo` (member and extension components), `_` entries and the
// backtick name `` `_` `` (a real variable), plus `withIndex()` of unsigned ranges.
class Span(val from: C, val to: C) { operator fun iterator() = Walk(from.i, to.i) }

class Walk(var c: Int, val last: Int) {
    operator fun hasNext(): Boolean = c <= last
    operator fun next(): C { val r = C(c); c++; return r }
}

class C(val i: Int) {
    operator fun component1() = i + 1
    operator fun component2() = "x$i"
    operator fun rangeTo(o: C) = Span(this, o)
}

class D(val n: Int)
class DSpan(val a: D, val b: D) { operator fun iterator() = listOf(a, b).iterator() }
operator fun D.rangeTo(o: D) = DSpan(this, o)
operator fun D.component1() = n * 10
operator fun D.component2() = n * 100

fun main() {
    var s = ""
    for ([a, b] in C(0)..C(2)) s += "$a:$b;"
    println(s)
    val fs = ArrayList<() -> String>()
    for ([a, _] in C(5)..C(6)) fs.add { "a=$a" }
    for ([_, `_`] in C(7)..C(7)) fs.add { "b=$`_`" }
    println(fs.map { it() })
    val r = C(1)..C(3)
    for (c in r) print("${c.i} ")
    println()
    for ([x, y] in D(1)..D(2)) print("$x/$y ")
    println()
    for ([i, u] in (3u..5u).withIndex()) print("$i=$u ")
    for ([i, u] in (10uL downTo 8uL).withIndex()) print("$i=$u ")
    println()
}
