// Inferred-return functions returning fresh references in a loop: every result released.
class Node(val v: Int) { fun next() = Node(v + 1); fun name() = "n" + v }
fun mk(i: Int) = Node(i)
fun fillN(a: ArrayList<String>, n: Int): ArrayList<String> { for (i in 0 until n) a.add(mk(i).name()); return a }
fun names(n: Int) = fillN(ArrayList<String>(), n)

fun main() {
    var acc = 0
    for (i in 0 until 500) { acc += mk(i).next().v; acc += names(i % 7).size }
    println(acc)
}
