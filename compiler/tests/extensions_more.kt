// Member, operator and generic extension functions; extension properties (issue #17).

var order = ""
fun mark(s: String, v: Int): Int { order += s; return v }

class Fmt(val sep: String) {
    fun Int.join(a: Int, b: Int = 0, c: String = "c"): String = "$this$sep$a$sep$b$sep$c"
    fun run(): String = 1.join(b = mark("b", 3), a = mark("a", 2)) + " " + 4.join(5, c = "z")
}

fun <T> List<T>.second(): T = this[1]
val <T> List<T>.firstOrSelf: T get() = this[0]

interface Step<T> {
    val zero: T
    fun T.next(): T
    fun T.twice(): T = next().next()
    fun count(n: Int): T { var x = zero; repeat(n) { x = x.next() }; return x }
}

object IntStep : Step<Int> {
    override val zero = 0
    override fun Int.next(): Int = this@next + 1
}

class StrStep(val s: String) : Step<String> {
    override val zero = ""
    override fun String.next(): String = this + s
}

interface Labels {
    val Int.label: String
    var Int.store: String
}

class Labeled : Labels {
    var last = ""
    override val Int.label: String get() = "#$this"
    override var Int.store: String
        get() = "$last@$this"
        set(v) { last = "$v$this" }
}

val <T> T.tag: String
    inline get() = "tag($this)"

class Box(var value: Int)

enum class Color { RED, GREEN }

class Acc(var total: Int) {
    operator fun plusAssign(x: Int) { total += x }
}

class Outer {
    fun shout(s: String) = s.loud()
    class Inner {
        fun shout(s: String) = s.loud()
    }
    companion object {
        private fun String.loud(): String = uppercase() + "!"
    }
}

class Holder(val k: Int) {
    fun Int.dbl() = this * 2 + k
    fun calc(): String {
        fun Int.tri() = dbl() * 3
        operator fun Box.plus(o: Box): Box = Box(value + o.value + k)
        return "${2.tri()} ${(Box(1) + Box(2)).value}"
    }
}

interface Sh<T> {
    fun T.show(): String
    fun all(xs: List<T>): String { var s = ""; for (x in xs) s += x.show(); return s }
}

class Gen<T>(val pre: String) : Sh<T> {
    override fun T.show(): String = pre + this
}

operator fun Int?.minus(x: Int): Int = (this ?: 100) - x

fun localOperators(): String {
    operator fun Color.contains(other: Color): Boolean = this == other
    operator fun Box.get(i: Int): Int = value * i
    operator fun Box.plusAssign(i: Int) { value += i }
    operator fun Int?.inc(): Int = (this ?: 0) + 10
    val b = Box(3)
    b += 4
    var n: Int? = null
    n++
    val hit = when (Color.RED) { in Color.GREEN -> "green"; in Color.RED -> "red"; else -> "none" }
    return "${b[2]} $n $hit"
}

fun main() {
    println(Fmt("-").run())
    println(order)
    val words = listOf("x", "y", "z")
    val sec = words::second
    val first = words::firstOrSelf
    println(sec() + first())
    println(IntStep.count(3))
    println(with(IntStep) { 5.twice() })
    println(StrStep("ab").count(2))
    with(Labeled()) {
        println(7.label)
        8.store = "s"
        println(9.store)
    }
    println("k".tag + 1.tag)
    val acc = Acc(1)
    acc += 5
    println(acc.total)
    println(Outer().shout("hi") + Outer.Inner().shout("yo"))
    val m: Int? = null
    println(m - 1)
    println(localOperators())
    println(Holder(1).calc())
    println(Gen<Int>("n").all(listOf(1, 2)))
}
