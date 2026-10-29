// Expression-bodied functions and methods without a declared return type.
class Pt(val x: Int, val y: Int) {
    fun sum() = x + y
    fun scaled(k: Int) = Pt(x * k, y * k)
    fun label() = "Pt(" + x + "," + y + ")"
}

fun twice(n: Int) = n * 2
fun quad(n: Int) = twice(twice(n))
fun big(n: Long) = n * 1000000000L
fun half(d: Double) = d / 2.0
fun isEven(n: Int) = n % 2 == 0
fun greet(s: String) = "hi " + s
fun pick(b: Boolean) = if (b) "yes" else "no"
fun fill(a: ArrayList<Int>, n: Int): ArrayList<Int> { for (i in 0 until n) a.add(i); return a }
fun list(n: Int) = fill(ArrayList<Int>(), n)
fun origin() = Pt(0, 0)

fun main() {
    println(quad(3))
    println(big(5L))
    println(half(5.0))
    println(isEven(4))
    println(greet("x"))
    println(pick(false))
    println(list(4).size)
    val p = Pt(2, 3).scaled(2)
    println(p.sum())
    println(p.label())
    println(origin().sum())
}
