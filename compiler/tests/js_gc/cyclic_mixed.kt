// Garbage cycles that own acyclic objects (--target=js: acyclic classes have no trav/relo hooks; the collector
// releases them through rel): the acyclic payloads hold strings, lists and other acyclic objects.
class Tag(val name: String, val parts: MutableList<String>)
class Payload(val tag: Tag, val values: MutableList<Int>)
class Node(val id: Int, val payload: Payload) { var next: Node? = null }

fun ring(k: Int, n: Int): Int {
    val first = Node(k, Payload(Tag("t$k", mutableListOf("a", "b$k")), mutableListOf(k)))
    var cur = first
    for (i in 1 until n) {
        val nd = Node(k * 100 + i, Payload(Tag("t$i", mutableListOf("x$i")), mutableListOf(i, i * 2)))
        cur.next = nd; cur = nd
    }
    cur.next = first
    var s = 0; var p: Node? = first
    for (i in 0 until n) { s += p!!.payload.values.sum() + p.payload.tag.parts.size + p.payload.tag.name.length; p = p.next }
    return s
}

fun main() {
    var total = 0L
    for (k in 0 until 3000) total += ring(k, 1 + k % 7)
    println(total)
}
