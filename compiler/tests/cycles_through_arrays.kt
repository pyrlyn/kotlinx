// Reference cycles through Array<T?> elements (arrayOfNulls) are collected.
class Node(val name: String) {
    val kids = arrayOfNulls<Node>(2)
}
fun main() {
    for (i in 0 until 3) {
        val a = Node("a"); val b = Node("b")
        a.kids[0] = b; b.kids[0] = a
    }
    println("done")
}
