class It(val n: Int) {
    var i = 0
    operator fun hasNext() = i < n
    operator fun next(): Int { i++; return i }
}
class R(val n: Int) { operator fun iterator() = It(n) }
data class P(val a: Int, val b: String)
class PS { operator fun iterator() = listOf(P(1, "x"), P(2, "y")).iterator() }

fun main() {
    for (x in R(3)) print(x)
    println()
    outer@ for (x in R(4)) {
        if (x == 2) continue
        for (y in R(3)) { if (y == 2) continue@outer; print("$x$y ") }
        if (x == 3) break
    }
    println()
    for ((a, b) in PS()) print("$a$b ")
    println()
}
