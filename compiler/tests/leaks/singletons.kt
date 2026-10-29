// Objects and companions holding references: a registry object whose list and var field are replaced,
// companion factories returning fresh objects; singletons are released at exit under --leak-check.
class Node(val v: Int, var next: Node?) {
    companion object {
        var last: Node? = null
        fun make(v: Int): Node { val n = Node(v, last); last = Node(v, null); return n }
    }
}

object Registry {
    var items = ArrayList<Node>()
    var head: Node? = null
    fun add(n: Node) { items.add(n); head = n; if (items.size > 10) items = ArrayList<Node>() }
}

fun main() {
    var sum = 0L
    for (i in 0 until 1000) {
        val n = Node.make(i)
        Registry.add(n)
        Registry.add(Node(i, Registry.head))
        sum += n.v + Registry.items.size
        sum += Node.last!!.v
    }
    println(sum)
}
