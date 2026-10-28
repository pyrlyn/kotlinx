// Leak test: class bodies (plain constructor parameters, body properties, init blocks).
class Node(val v: Int)
open class Counter(start: Int, val name: String) {
    var count = start * 2
    val label: String = name + "!"
    var node = Node(start)
    private val log = ArrayList<Int>()
    init {
        val tmp = Node(count)
        count += tmp.v
        log.add(count)
    }
    fun inc(): Int { count++; log.add(count); node = Node(count); return count }
    fun size(): Int = log.size
}
fun main() {
    var s = 0
    for (i in 0 until 100) {
        val c = Counter(i, "c$i")
        c.inc(); c.inc()
        s += c.count + c.size() + c.node.v + c.label.length
    }
    println(s)
    val k = Counter(1, "k")
    println(k.label + " " + k.count + " " + k.size())
}
