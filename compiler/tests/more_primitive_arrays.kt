fun main() {
    val b = BooleanArray(3)
    b[1] = true
    println("${b[0]} ${b[1]} ${b.size}")
    val c = CharArray(3) { 'a' + it }
    c[2] = 'z'
    var s = ""
    for (ch in c) s += ch
    println(s)
    val bs = booleanArrayOf(true, false)
    println("${bs[0]} ${bs[1]}")
    val cs = charArrayOf('x', 'y')
    println(cs[1])
    val u = uintArrayOf(1u, 4000000000u)
    u[0] = u[0] + 2u
    println("${u[0]} ${u[1]}")
    val ul = ULongArray(2) { (it + 7).toULong() }
    println(ul[1])
    var n = 0
    for (x in b) if (x) n++
    println(n)
}
