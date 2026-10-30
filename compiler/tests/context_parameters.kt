// Context parameters: functions, extensions, operators, members, local functions, top-level computed properties.
class Logger(val prefix: String) { fun log(s: String) = println(prefix + s) }
class Scope { fun hello(): String = "member" }
data class Box(var v: Int)
var store = ""

context(l: Logger)
fun work(x: Int): Int { l.log("work $x"); return x * 2 }

context(n: Int)
operator fun Box.unaryMinus(): Box = Box(-v * n)

context(_: Logger)
fun Box.show(): Int = work(v)

fun hello(): String = "top-level"

context(c: String)
var tagged: String
    get() = store
    set(value) { store = c + value }

fun main() {
    with(Logger("> ")) {
        println(work(21))
        println(Box(4).show())
    }
    with(3) { println((-Box(2)).v) }
    with(Scope()) { println(hello()) }
    context(l: Logger)
    fun local(): Int = work(1)
    println(with(Logger("# ")) { local() })
    with("O") { tagged = "K"; println(tagged) }
    println(+5)
}
