fun main() {
    for ((i, s) in listOf("a", "b").asSequence().withIndex()) println("$i$s")
    val e = ArrayList<Int>().asSequence()
    println(e.size)
}
