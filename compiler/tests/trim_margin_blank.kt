fun main() {
    println("|a\n  |b".trimMargin())
    try {
        "a b c".trimMargin(" ")
        println("no throw")
    } catch (e: IllegalArgumentException) {
        println("caught: " + e.message)
    }
}
