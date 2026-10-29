// Feature coverage for the Kotlin-X compiler subset; expected output in features.out.
class Node(val value: Long, var next: Node?)
class Box(var items: ArrayList<Long>, var label: String)

fun classify(n: Int): String = when {
    n < 0 -> "negative"
    n == 0 -> "zero"
    else -> "positive"
}

fun digit(n: Int): String = when (n) {
    1, 2 -> "small"
    3 -> "three"
    else -> "other"
}

fun max(a: Long, b: Long): Long = if (a > b) a else b

fun sumTo(n: Int): Long {
    var s = 0L
    for (i in 1..n) s += i
    return s
}

fun makeList(n: Int): ArrayList<Long> {
    val l = ArrayList<Long>()
    for (i in 0 until n) l.add(i.toLong() * i)
    return l
}

fun main() {
    println("hello")
    println(classify(-5) + " " + classify(0) + " " + classify(7))
    println("${digit(1)} ${digit(3)} ${digit(9)}")
    println(max(3L, 9L))
    println(sumTo(100))
    val l = makeList(5)
    var t = 0L
    for (x in l) t += x
    println("size=${l.size} total=$t first=${l[1]} get=${l.get(4)}")
    l[0] = 42L
    println(l[0])
    val nested = ArrayList<ArrayList<Long>>()
    for (k in 0 until 3) nested.add(makeList(k + 1))
    println(nested.size + nested[2].size)
    val b = Box(makeList(2), "box")
    b.items = makeList(3)
    b.label = "renamed"
    println("${b.label} ${b.items.size}")
    val d = 1.5 * 2 + 0.25
    println(d)
    println(10 / 3.0 > 3)
    println(7 % 3 == 1 && !(2 > 3) || false)
    println((0xFF and 0x0F) + (1 shl 4) + (-16 shr 2) + (5 xor 1))
    var i = 0
    while (i < 3) { i++ }
    println(i)
    val big = Long.MAX_VALUE
    println(big + 1)
    val c = 'A'.code
    println(c)
    val name: String? = null
    println(name ?: "default")
    val bytes = ByteArray(4)
    bytes[2] = 7
    var bs = 0
    for (x in bytes) bs += x
    println("bytes=${bytes.size} sum=$bs")
}
