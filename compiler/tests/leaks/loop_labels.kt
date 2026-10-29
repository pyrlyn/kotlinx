fun main() {
    val sb = StringBuilder()
    outer@ for (i in 1..4) {
        for (j in 1..4) {
            if (j == 3) continue@outer
            if (i == 3) break@outer
            sb.append("$i$j ")
        }
    }
    println(sb.toString())
    var n = 0
    loop@ while (true) {
        n++
        var k = 0
        inner@ do {
            k++
            if (k == 2) continue@inner
            if (n == 3) break@loop
            if (k > 3) break@inner
        } while (true)
    }
    println(n)
    val xs = listOf("a", "b", "c")
    l@ for ((idx, s) in xs.withIndex()) { if (s == "b") continue@l; print("$idx$s ") }
    println()
}
