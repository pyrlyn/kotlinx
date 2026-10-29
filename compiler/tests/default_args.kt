// Default and named arguments: functions, generic functions, constructors, methods (defaults using members), interface defaults, lambdas.
fun greet(name: String, greeting: String = "Hello", punct: String = "!"): String = greeting + ", " + name + punct
fun range(from: Int = 0, to: Int = from + 10): Int = to - from
fun <T> wrap(x: T, times: Int = 2): List<T> { val l = ArrayList<T>(); for (i in 0 until times) l.add(x); return l }

interface Logger { fun log(msg: String, level: Int = 1): String }
class Console : Logger { override fun log(msg: String, level: Int): String = "[" + level + "] " + msg }

class Rect(val w: Int = 1, val h: Int = w * 2) {
    var scale = 3
    fun area(factor: Int = scale): Int = w * h * factor
    fun describe(prefix: String = "rect", sep: String = ":"): String = prefix + sep + area()
}
data class Opt(val a: Int = 1, val b: String = "x", val c: Boolean = false)

fun apply(x: Int, f: (Int) -> Int = { it * 10 }): Int = f(x)

fun main() {
    println(greet("Ann"))
    println(greet("Bob", "Hi"))
    println(greet("Cy", punct = "?"))
    println(greet(punct = ".", name = "Di"))
    println(range())
    println(range(5))
    println(range(to = 3))
    println(wrap("z"))
    println(wrap(1, times = 3))
    val l: Logger = Console()
    println(l.log("boot"))
    println(Console().log("x", level = 5))
    println(Rect().area())
    println(Rect(3).area(1))
    println(Rect(h = 5).describe())
    println(Rect(2, 2).describe(sep = "="))
    println(Opt())
    println(Opt(b = "y"))
    println(Opt(2, c = true))
    println(apply(4))
    println(apply(4) { it + 1 })
}
