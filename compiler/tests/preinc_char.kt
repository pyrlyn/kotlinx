class Box(var n: Int)

fun main() {
    var a = 5
    val b = ++a
    println("$a $b")
    val c = --a
    println("$a $c")
    val box = Box(1)
    println(++box.n)
    val arr = intArrayOf(10, 20)
    println(++arr[1])
    var i = 0
    while (i < 3) ++i
    println(i)
    val lo: Char = Char.MIN_VALUE
    val hi: Char = Char.MAX_VALUE
    println(lo.code)
    println(hi.code)
    println((lo - 1).code)
    println((hi + 1).code)
    var ch = 'a'
    println(++ch)
}
