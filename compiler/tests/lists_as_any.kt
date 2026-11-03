class P(val n: Int)

fun typeName(a: Any?): String = when (a) {
    is ArrayList<*> -> "array list"
    else -> "no idea"
}

fun main() {
    println(typeName(ArrayList<Int>()))
    println(typeName(listOf("a")))
    println(typeName(listOf(P(1))))
    println(typeName(3))
    val o: Any = listOf(4, 5)
    val back = o as ArrayList<Int>
    println(back[1] + 1)
    val ps: Any = listOf(P(7))
    println((ps as ArrayList<P>)[0].n)
    println(o == o)
    println(o is ArrayList<Int>)
    val xs = ArrayList<Any>()
    xs.add(listOf("s"))
    println((xs[0] as ArrayList<String>)[0])
}
