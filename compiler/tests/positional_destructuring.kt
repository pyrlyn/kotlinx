data class P(val a: String, val b: Int)

fun box(): String {
    val [x, y] = P("o", 1)
    var [s, _] = "a" to "z"
    s += "b"
    val [val m, var n] = P("m", 2)
    n += 1
    for ([i, v] in listOf("p", "q").withIndex()) println("$i$v")
    for ((k, w) in listOf(P("r", 3))) println("$k$w")
    val f = { [a, b]: P -> a + b }
    val g: (P, Int) -> String = { (a, b), c -> "$a${b + c}" }
    println(listOf(P("t", 4)).map { [a, _] -> a + "!" })
    println("$x$y $s $m$n ${f(P("u", 5))} ${g(P("v", 6), 1)}")
    return "OK"
}

fun main() { println(box()) }
