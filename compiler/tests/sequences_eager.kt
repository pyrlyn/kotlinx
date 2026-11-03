fun firsts(s: Sequence<String>): String { var r = ""; for (x in s) r += x[0]; return r }

fun main() {
    val s = sequenceOf("ab", "cd")
    println(firsts(s))
    for ((i, x) in (1..3).asSequence().withIndex()) print("$i$x ")
    println()
    println(listOf(1, 2).asSequence().size)
}
