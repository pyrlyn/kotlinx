// Value classes (currently boxed): wrappers around strings and objects compared structurally, stored in lists,
// returned from functions and printed; the wrapped references must be released with the wrapper.
class Node(val v: Int)
value class Name(val s: String)
value class Ref(val n: Node)

fun mk(i: Int): Name = Name("n" + i)

fun main() {
    var sum = 0L
    val names = ArrayList<Name>()
    for (i in 0 until 1000) {
        val a = mk(i)
        if (a == Name("n" + i)) sum += 1
        names.add(a)
        val r = Ref(Node(i))
        sum += r.n.v
        sum += ("" + mk(i % 10)).length
        if (names.size > 5) names.removeAt(0)
    }
    println(sum)
}
