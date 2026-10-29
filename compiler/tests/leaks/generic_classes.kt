// Generic classes over reference types: instances holding objects, strings and lists, a generic var cell
// overwritten in a loop, a generic stack with removeAt moving ownership out, and nested instances.
class Node(val v: Int, var next: Node?)

class Box<T>(val value: T) {
    fun get(): T = value
}

class Cell<T>(var v: T) {
    fun set(x: T) { v = x }
}

class Stack<T> {
    private val items = ArrayList<T>()
    fun push(x: T) { items.add(x) }
    fun pop(): T = items.removeAt(items.size - 1)
    fun size(): Int = items.size
}

class Pair2<A, B>(val first: A, val second: B)

fun main() {
    var sum = 0L
    val cell = Cell(Node(0, null))
    for (i in 0 until 1000) {
        val b = Box(Node(i, Node(1, null)))
        sum += b.get().v
        cell.set(Node(i, cell.v))
        cell.v = Node(i, null)
        val st = Stack<Node>()
        st.push(Node(i, null)); st.push(Node(2, null)); st.push(Node(3, null))
        sum += st.pop().v + st.size()
        val s = Box("s" + i)
        sum += s.value.length
        val p = Pair2(Box(Node(5, null)), "x" + i)
        sum += p.first.value.v + p.second.length
        val nested = Box(Box(Box(Node(i, null))))
        sum += nested.get().get().get().v
        Box(Node(9, null))
    }
    sum += cell.v.v
    println(sum)
}
