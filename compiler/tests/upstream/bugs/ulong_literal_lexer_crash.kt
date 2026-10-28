// BUG: newkotlinc crashes in Lexer.lex with kotlin.NumberFormatException on a ULong literal > Long.MAX_VALUE.
// Expected: compiles, prints 18446744073709551615
// Actual:   compiler crash (uncaught NumberFormatException), no diagnostic.
// Upstream: box/unsignedTypes/unsignedLongToString.kt, ranges/stepped/unsigned/*MaxValue*, evaluate/ulongOperations.kt (12 tests)
fun main() {
    val max = 18446744073709551615UL
    println(max)
}
