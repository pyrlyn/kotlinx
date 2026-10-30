// Local classes capturing local vals and parameters of the enclosing function.
fun make(prefix: String, n: Int): String {
    val sep = "-"
    class Item(val id: Int) {
        val label = prefix + sep + id
        fun next(): Item = Item(id + n)
    }
    return Item(1).next().next().label
}

open class Base(val s: String)

fun sub(arg: String): String {
    val t = "local"
    class Z : Base(arg) {
        fun both() = t + "/" + this.s + "/" + arg
    }
    return Z().both()
}

fun main() {
    println(make("x", 10))
    println(sub("base"))
    val k = 3
    class Counter { var c = k; fun inc() { c += k } }
    val c = Counter(); c.inc(); c.inc()
    println(c.c)
}
