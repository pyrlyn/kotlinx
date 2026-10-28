// BUG: postfix increment on a Char inside a string template yields the wrong value/type.
// Expected: ax then b   Actual: 97x then 97 (Char++ widened to Int, variable not incremented)
// Upstream: box/strings/kt3652.kt
fun main() {
    var a = 'a'
    println("${a++}x")
    println(a)
}
