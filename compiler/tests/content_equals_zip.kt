fun main() {
    println(intArrayOf(1, 2).contentEquals(intArrayOf(1, 2)))
    println(intArrayOf(1, 2).contentEquals(intArrayOf(1, 3)))
    println(arrayOf("a").contentEquals(arrayOf("a")))
    println(charArrayOf('x').contentEquals(charArrayOf()))
    val z = listOf(1, 2, 3).zip(listOf("a", "b"))
    println(z)
    for ((n, s) in z) print("$n$s ")
    println()
}
