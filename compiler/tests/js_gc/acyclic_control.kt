// Structured control flow on --target=js after the label peephole (tail breaks and continues dropped, unused
// labels removed): nested loops with labeled break and continue, when chains, early returns and do-while.
fun classify(n: Int): Int = when {
    n % 15 == 0 -> 3
    n % 5 == 0 -> 2
    n % 3 == 0 -> 1
    else -> 0
}

fun firstPair(limit: Int): String {
    outer@ for (i in 1..limit) {
        for (j in i..limit) {
            if ((i * j) % 7 == 3 && i + j > 10) return "$i,$j"
            if (j - i > 5) continue@outer
        }
    }
    return "none"
}

fun main() {
    var s = 0L
    outer@ for (i in 0 until 100) {
        var j = 0
        while (true) {
            j++
            if (j > i) break
            if ((i + j) % 4 == 0) continue
            if (i * j > 3000) break@outer
            s += classify(i * j)
        }
    }
    println(s)
    println(firstPair(40))
    var k = 0; var acc = 1
    do { acc = acc * 3 % 1000003; k++ } while (acc != 1 && k < 100000)
    println("$k $acc")
    val counts = IntArray(4)
    for (n in 1..1000) counts[classify(n)]++
    println("${counts[0]} ${counts[1]} ${counts[2]} ${counts[3]}")
}
