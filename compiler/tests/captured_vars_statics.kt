// Captured vars (boxCapturedVars) and static references (rewriteStatics): each case prints what Kotlin prints.
fun call(f: () -> Unit) = f()
fun calli(f: () -> Int): Int = f()
fun mk(): () -> Int { var n = 1; val g = { n * 10 }; n = 5; return g }
fun counter(): () -> Int { var c = 0; return { c++; c } }
fun inlineOnly(): Int { var s = 0; listOf(1, 2, 3).forEach { s += it }; return s }
fun shadowParam(): Int { var x = 1; val f = { x: Int -> x + 100 }; x = 2; return f(x) }
fun shadowIt(): Int { var it = 7; val f: (Int) -> Int = { it * 2 }; it = 8; return f(3) + it }
fun inBranches(b: Boolean): Int {
    var r = 0
    if (b) call { r += 1 } else call { r += 2 }
    when (r) { 1 -> call { r += 10 }; else -> call { r += 20 } }
    try { call { r += 100 } } catch (e: RuntimeException) { r = -1 }
    return r
}
fun nested(): Int { var a = 1; call { call { a += 2 } }; return a }
fun localVal(): Int { var v = 3; call { val v = 9; println(v) }; return v }
fun exprBody(): Int = calli { var q = 1; call { q = 4 }; q }
var g = 0
fun bumpGlobal(): Int { call { g += 5 }; return g }
class Holder {
    var total = 0
    init { var k = 2; call { k *= 3 }; total = k }
    val lazyish = calli { var m = 1; call { m += 41 }; m }
}
enum class Color { RED, GREEN, BLUE;
    fun next(): Color = if (this == RED) GREEN else if (this == GREEN) BLUE else RED
}
enum class Op { ADD { override fun ap(a: Int, b: Int) = a + b }, MUL { override fun ap(a: Int, b: Int) = a * b };
    abstract fun ap(a: Int, b: Int): Int
}
class Conf(val name: String) {
    val size = 3
    fun describe(): String = name + ":" + limit + ":" + size + ":" + Conf.limit + ":" + Conf.Companion.limit
    companion object {
        val limit = 10
        val size = 99
        fun twice(): Int = Conf.limit * 2
    }
}
fun main() {
    println(mk()()); val c = counter(); c(); println(c()); println(inlineOnly()); println(shadowParam()); println(shadowIt())
    println(inBranches(true)); println(inBranches(false)); println(nested()); println(localVal()); println(exprBody())
    println(bumpGlobal()); println(bumpGlobal())
    val h = Holder(); println(h.total); println(h.lazyish)
    println(Color.RED.next()); println(Color.BLUE.next()); println(Color.values().size); println(Color.entries.size); println(Color.GREEN.ordinal)
    println(Op.ADD.ap(2, 3)); println(Op.MUL.ap(2, 3))
    println(Conf("c").describe()); println(Conf.twice()); println(Conf.size); println(Conf("d").size)
}
