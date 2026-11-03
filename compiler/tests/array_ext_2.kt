fun main() {
    val a = intArrayOf(1, 2, 3)
    println(a.contentToString())
    println(charArrayOf('x', 'y').contentToString())
    println(booleanArrayOf().contentToString())
    println(a.toList())
    println(longArrayOf(5L, 6L).reversed())
    println(listOf(3, 1).reversed())
    println(listOf("p").toList())
    val l = a.toList()
    l.add(4)
    println("${l.size} ${a.size}")
}
