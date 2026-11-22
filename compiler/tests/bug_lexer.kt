/* Block comments nest, as in Kotlin: /* inner */ this is still a comment. */
/* /* /* three levels */ */ */
fun main() {
    // Unicode escapes in strings and chars.
    println("\u0041\u00e9\u20AC|\t|")
    val c = '\u0042'
    println(c)
    println("\u0024{not a template}")
    println("b\bs".length)
    // Templates containing string literals with braces, char literals with braces and nested templates.
    println("${"}"}")
    println("[${"{" + "}"}]")
    println("${'{'}${'}'}")
    val n = 3
    println("outer ${"inner ${n + 1} ${"${n}"}"} done")
    println("map: ${listOf(1, 2).map { "{<$it>}" }}")
    println("""raw ${"}"} ${n}""")
    println("x${ /* } */ n }y")
}
