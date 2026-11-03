fun main() {
    val a = intArrayOf(1, 2, 3).reversedArray()
    println("${a[0]}${a[1]}${a[2]} ${a.size}")
    val c = charArrayOf('a', 'b').reversedArray()
    println("${c[0]}${c[1]}")
    val d = doubleArrayOf(1.5).copyOf()
    d[0] = 2.5
    println(d[0])
    println(listOf(1).isEmpty())
    for ((i, v) in booleanArrayOf(true, false).withIndex()) println("$i $v")
    for ((i, v) in longArrayOf(7L).withIndex()) println("$i $v")
    capacity()
}

fun capacity() {
    val l = ArrayList<String>(16)
    l.add("x")
    println("${l.size} ${l[0]}")
}
