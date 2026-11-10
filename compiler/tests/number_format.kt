// Number formatting of the runtime (println and string templates of Double, Float, Long, Int): shortest
// round-trip digits, plain vs scientific notation, special values. The runtime formats these without printf.
fun main() {
    println(0.1 + 0.2)
    println(1.0 / 3)
    println(-2.0 / 3)
    println(0.0)
    println(-0.0)
    println(1.0 / 0.0)
    println(-1.0 / 0.0)
    println(0.0 / 0.0)
    println(100.0)
    println(1e7)
    println(9999999.0)
    println(1e-3)
    println(9.99e-4)
    println(123456789.125)
    println(4.9e-324)
    println(1.7976931348623157e308)
    println(2.2250738585072014e-308)
    var p = 1.0
    for (i in 0 until 70) { p *= 3.0; if (i % 7 == 0) println("3^${i + 1} = $p") }
    var q = 1.0
    for (i in 0 until 40) { q /= 7.0; if (i % 5 == 0) println("7^-${i + 1} = $q") }
    var d = 0.5
    for (i in 0 until 30) { d = d * 1.37 + 0.011; if (i % 3 == 0) println(d) }
    val f = 0.1f
    println(f)
    println(1.0f / 3.0f)
    println(16777216.0f)
    println(3.4028235e38f)
    println(1.4e-45f)
    var g = 1.0f
    for (i in 0 until 20) { g = g * 1.7f + 0.3f; if (i % 4 == 0) println("g=$g") }
    println(Long.MAX_VALUE)
    println(Long.MIN_VALUE)
    println(-1234567890123L)
    println(Int.MIN_VALUE)
    println(0L)
    val s = "x=${1.5} y=${-7L} z=${2.5e-7} w=${1e21}"
    println(s)
}
