class P(val n: Int) {
    override fun toString() = "P$n"
}

fun pick(k: Int): Any = when (k) {
    0 -> "str" + k
    1 -> 10L + k
    2 -> P(k)
    3 -> 2.5
    else -> 'c'
}

fun describe(x: Any): String {
    val r = when (x) {
        is String -> x.length
        is Long -> x.toString() + "L"
        else -> P(0)
    }
    return r.toString()
}

fun main() {
    for (k in 0..4) println(pick(k))
    val flag = pick(0) is String
    val y = if (flag) "yes" else 0
    println(y)
    println(describe("four"))
    println(describe(7L))
    println(describe(1.0))
    var total = 0
    for (i in 0 until 1000) {
        val v = if (i % 3 == 0) "a$i" else if (i % 3 == 1) P(i) else i
        total += v.toString().length
    }
    println(total)
}
