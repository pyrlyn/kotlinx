typealias Names = List<String>
typealias Num = Int
annotation class Marker(val s: String)

fun total(xs: List<Num>): Num {
    var s: Num = 0
    for (x in xs) s += x
    return s
}

@Marker("x")
fun join(ns: Names): String {
    val sb = StringBuilder()
    for (n in ns) sb.appendLine(n)
    sb.appendLine()
    sb.append("end")
    return sb.toString()
}

fun main() {
    val n: String? = null
    println(n ?: total(listOf(1, 2, 3)).toString())
    val m: String? = "x"
    val r = m ?: 1.toString()
    println(r + (n ?: "y"))
    print(join(listOf("a", "b")))
    println()
}
