fun describe(x: Any): String = when (x) {
    is IntArray -> "ints ${x.size} ${x[0]}"
    is CharArray -> "chars ${x[0]}"
    is String -> "str $x"
    else -> "other"
}

fun main() {
    val a = intArrayOf(4, 5)
    println(describe(a))
    println(describe(charArrayOf('q')))
    println(describe("s"))
    println(describe(longArrayOf(1L)))
    val o: Any = a
    println(o == a)
    println(o == intArrayOf(4, 5))
    val back = o as IntArray
    back[0] = 9
    println(a[0])
    val xs = ArrayList<Any>()
    xs.add(a)
    println((xs[0] as IntArray)[1])
}
