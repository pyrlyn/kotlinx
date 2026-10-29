// Parser gaps: ..<, [a, b] destructuring in for, withIndex, raw strings, trimIndent/trimMargin, diagnostic markup.
fun main() {
    for (i in 1..<4) print(i)
    println()
    val xs = arrayListOf("a", "b", "c")
    for ([i, x] in xs.withIndex()) println("" + i + "=" + x)
    for ((i, x) in xs.withIndex()) if (i == 1) println(x)
    val raw = """
        line1
          line2 ${xs.size}
    """
    println(raw.length)
    println("""
        |a
        |b
    """.trimMargin())
    println("""
        x
          y
    """.trimIndent())
    println("""q"""")
    println(<!UNUSED!>"diag"<!>)
}
