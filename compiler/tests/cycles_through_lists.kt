// Reference cycles through ArrayList elements are collected: the cycle analysis counts list elements as children
// of the owning object, and the collector traverses a list while the object is its only owner. (Default and
// incremental collectors; with deferred RC (epoch) lists are not traversed.)
class Node(val name: String) {
    val children = ArrayList<Node>()
    var parent: Node? = null
}

class A { val list = ArrayList<B>() }
class B(val a: A)

class Graph { val nodes = ArrayList<Vertex>() }
class Vertex(val id: Int) { val edges = ArrayList<Vertex>() }

fun tree(depth: Int, name: String): Node {
    val n = Node(name)
    if (depth > 0) for (i in 0 until 2) {
        val c = tree(depth - 1, name + i)
        c.parent = n
        n.children.add(c)
    }
    return n
}

fun count(n: Node): Int {
    var s = 1
    for (c in n.children) s += count(c)
    return s
}

fun main() {
    // Back pointer as a val: the cycle closes only through the list.
    for (i in 0 until 1000) { val a = A(); a.list.add(B(a)); a.list.add(B(a)) }
    // Parent pointers plus child lists.
    var total = 0
    for (i in 0 until 100) total += count(tree(4, "t"))
    println(total)
    // A dense graph: every vertex reaches every other one through lists only.
    for (r in 0 until 50) {
        val g = Graph()
        for (i in 0 until 8) g.nodes.add(Vertex(i))
        for (v in g.nodes) for (w in g.nodes) v.edges.add(w)
        if (r == 49) println(g.nodes[3].edges.size)
    }
    // A list that is still shared when its owner dies is released normally.
    val shared: ArrayList<B>
    run {
        val a = A()
        a.list.add(B(a))
        shared = a.list
    }
    println(shared.size)
    // Self-cycle through its own list.
    for (i in 0 until 100) { val v = Vertex(i); v.edges.add(v) }
    println("done")
}
