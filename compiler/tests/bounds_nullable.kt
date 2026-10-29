// Generic bounds, null branches of generic results, String/Comparable ordering and compareTo, nullable receivers, !!.
interface Named { fun name(): String }
class A(val n: String) : Named { override fun name(): String = n }
fun <T : Named> show(x: T): String = x.name()
class Holder<T : Named>(val v: T) { fun get(): String = v.name() }
fun <T : Comparable<T>> maxOf2(a: T, b: T): T = if (a > b) a else b
fun <T> firstOrNull2(xs: List<T>): T? = if (xs.size == 0) null else xs[0]
fun main() {
    println(show(A("a")))
    println(Holder(A("b")).get())
    println(maxOf2(3, 5))
    println(maxOf2("x", "b"))
    val s: String? = firstOrNull2(listOf("q"))
    println(s)
    more()
}

class Ver(val major: Int, val minor: Int) : Comparable<Ver> {
    override fun compareTo(other: Ver): Int = if (major != other.major) major - other.major else minor - other.minor
    override fun toString(): String = "" + major + "." + minor
}
fun String?.orEmpty2(): String = if (this == null) "" else this
fun <T> pick(c: Boolean, a: T): T? = if (c) a else null

fun more() {
    println(maxOf2(Ver(1, 2), Ver(1, 10)))
    println(Ver(2, 0) > Ver(1, 9))
    println("apple" < "banana")
    println("a".compareTo("c"))
    println("abc".compareTo("ab"))
    println(3.compareTo(7))
    val n: String? = null
    println(n.orEmpty2() + "|" + "k".orEmpty2())
    val p = pick(true, "yes")
    println(p!!.length)
    println(pick(false, Ver(0, 1)) == null)
    try {
        val q: String? = pick(false, "no")
        println(q!!.length)
    } catch (e: NullPointerException) {
        println("NPE")
    }
}
