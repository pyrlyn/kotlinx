fun main() {
    val it = intArrayOf(1, 2, 3).iterator()
    var s = 0
    while (it.hasNext()) s += it.next()
    println(s)
    val c = charArrayOf('o', 'k').iterator()
    var t = ""
    while (c.hasNext()) t += c.next()
    println(t)
    val l = listOf("a", "b").iterator()
    while (l.hasNext()) print(l.next())
    println()
    val b = byteArrayOf(7).iterator()
    println("${b.next()} ${b.hasNext()}")
}
