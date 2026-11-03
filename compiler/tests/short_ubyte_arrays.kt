fun main() {
    val s = shortArrayOf(1, -2, 300)
    s[0] = (s[0] + 10).toShort()
    println("${s[0]} ${s[1]} ${s[2]} ${s.size}")
    val z = ShortArray(2)
    println(z[1])
    val u = ubyteArrayOf(1u, 255u)
    println("${u[0]} ${u[1]}")
    val w = UShortArray(2) { (it + 40000).toUShort() }
    println(w[1])
    var t = 0
    for (x in s) t += x
    println(t)
}
