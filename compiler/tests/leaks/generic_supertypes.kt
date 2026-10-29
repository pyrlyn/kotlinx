interface Source<T> { fun next(): T }
class Words(val items: ArrayList<String>) : Source<String> {
    var i = 0
    override fun next(): String { val w = items[i % items.size] + "!"; i++; return w }
}
class Holder<T>(var v: T) : Source<T> { override fun next(): T = v }
abstract class Base<T>(val tag: T) { abstract fun get(): T }
class Named(val n: String) : Base<String>(n + "?") { override fun get(): String = tag + n }
fun <T> pull(s: Source<T>): T = s.next()
fun main() {
    var total = 0
    for (k in 0 until 200) {
        val l = ArrayList<String>(); l.add("a$k"); l.add("bb")
        val srcs = ArrayList<Source<String>>()
        srcs.add(Words(l)); srcs.add(Holder("h$k"))
        for (s in srcs) total += pull(s).length
        val b: Base<String> = Named("x$k")
        total += b.get().length
        val hl = Holder(l)
        total += pull(hl).size
    }
    println(total)
}
