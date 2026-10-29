// Progressions with reference work in the body: objects created per step, break/continue leaving scopes.
class Node(val v: Int, var next: Node?)

fun main() {
    var head: Node? = null
    var sum = 0L
    for (i in 1000 downTo 1 step 3) {
        val n = Node(i, head)
        if (i % 5 == 0) continue
        head = n
        val s = "x" + i
        if (s.length > 10) break
        sum += n.v
    }
    for (i in 0 until 600 step 7) { val t = Node(i, null); sum += t.v; if (i > 500) break }
    for (c in 'a'..'z' step 5) sum += ("" + c).length
    println(sum)
}
