fun main() {
    val sb = StringBuilder()
    for (i in 10 downTo 1) sb.append(i).append(' ')
    println(sb.toString())
    var s = ""
    for (i in 0..10 step 3) s += i
    println(s)
    s = ""
    for (i in 0 until 10 step 4) s += i
    println(s)
    s = ""
    for (i in 10 downTo 0 step 3) s += "$i,"
    println(s)
    var n = 0
    for (i in 5 downTo 6) n++
    for (i in 1 until 1) n++
    for (i in 3..2) n++
    println(n)
    s = ""
    for (c in 'a'..'e') s += c
    for (c in 'z' downTo 'w' step 2) s += c
    println(s)
    var cnt = 0
    for (i in 2147483640..2147483647 step 3) cnt++
    for (i in -2147483647 - 1 until -2147483640) cnt++
    for (i in (-2147483647 - 1) + 5 downTo -2147483647 - 1 step 2) cnt++
    println(cnt)
    var sum = 0L
    for (i in 0L..1000000L step 7L) sum += i
    println(sum)
    for (i in 0..20 step 5) { if (i == 10) continue; if (i == 20) break; print(i); print(' ') }
    println()
    for (i in 1..3 step 0) println(i)
}
