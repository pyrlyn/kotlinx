// return / break / continue used as expressions.
fun f(x: String?): Int { val y = x ?: return 0; return y.length }
fun g(xs: List<String?>): String {
    var res = ""
    for (x in xs) res += x ?: break
    return res
}
fun main() {
    println(f(null)); println(f("ab"))
    var s = 0
    for (i in 0..5) { val v = if (i % 2 == 0) i else continue; s += v }
    println(s)
    println(g(listOf("a", "b", null, "c")))
    var n = 0
    outer@ while (true) { n++; val z = if (n > 3) break@outer else n; s += z }
    println(n)
}
