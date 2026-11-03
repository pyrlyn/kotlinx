data class P(val a: String, val b: Int)

var log = ""
operator fun Long.get(i: Int): Long = this * 10 + i
operator fun Long.set(i: Int, v: Long) { log += "set$i=$v " }
operator fun Int.get(i: Int): Int = this + i

fun main() {
    [val a, var b] = P("x", 1)
    b += 1
    [val c: String] = P("y", 9)
    println("$a$b$c")
    val n = 4L
    println(n[2])
    n[3] = 7L
    var m = 5L
    val y = m[0]++
    println("$y $log ${3[4]}")
}
