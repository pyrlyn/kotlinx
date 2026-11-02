class Counter(var n: Int) {
    inline fun twice(f: (Int) -> Int): Int = f(f(n))
    tailrec fun sumTo(k: Int, acc: Long): Long = if (k == 0) acc else sumTo(k - 1, acc + k)
    private inline fun bump() { n++ }
    fun step(): Int { bump(); return n }
}

interface Shape {
    fun area(): Int
}

class Sq(val s: Int) : Shape {
    override inline fun area(): Int = s * s
}

object Util {
    tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
}

fun main() {
    val c = Counter(3)
    println(c.twice { it + 1 })
    println(c.sumTo(100000, 0L))
    println(c.step())
    val s: Shape = Sq(4)
    println(s.area())
    println(Util.gcd(84, 36))
}
