fun main() {
    val s: MutableSet<Int> = mutableSetOf(1, 2, 1)
    println("${s.add(3)} ${s.add(2)} $s ${s.size} ${s.contains(2)} ${s.contains(9)}")
    for (x in s) print(x)
    println()
    val h = HashSet<String>()
    h.add("b"); h.add("a"); h.add("b")
    println("$h ${h.remove("b")} $h ${h.isEmpty()}")
    val t: Set<Int> = setOf(3, 2, 1)
    println(s == t)
    println(s.equals(t))
    println(listOf(5, 5, 6).toSet())
}
