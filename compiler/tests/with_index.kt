// withIndex() of strings, IntArray and progressions.
fun main() {
    for ((i, c) in "ab".withIndex()) println("$i $c")
    for ((i, x) in (10..12).withIndex()) println("$i $x")
    for ((i, x) in (0..6 step 3).withIndex()) println("$i $x")
    val a = IntArray(2); a[1] = 7
    for ((i, x) in a.withIndex()) println("$i $x")
    println(listOf(5, 6).withIndex().size)
}
