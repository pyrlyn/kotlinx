// List methods: contains/indexOf/remove/-=/first/last/isEmpty/clear and `in`.
fun main() {
    val xs = mutableListOf(3, 1, 4, 1, 5)
    println(xs.contains(4))
    println(9 in xs)
    println(xs.indexOf(1))
    println(xs.lastIndexOf(1))
    xs -= 1
    println(xs)
    println(xs.remove(7))
    val ss = arrayListOf("a", "b", "c")
    ss -= "b"
    ss -= listOf("c")
    println(ss)
    println(ss.first() + ss.last())
    println(ss.isNotEmpty())
    ss.clear()
    println(ss.isEmpty())
    println(ss.firstOrNull())
}
