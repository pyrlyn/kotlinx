class MyError(val code: Int) : Exception("my error " + code)
class Res(val name: String)

fun risky(n: Int): Int {
    val r = Res("r" + n)
    if (n < 0) throw IllegalArgumentException("negative: " + n)
    if (n == 0) throw MyError(42)
    return n * 2 + r.name.length
}

fun parse(s: String): Int = try { if (s == "x") throw NumberFormatException("bad") else s.length } catch (e: NumberFormatException) { -1 }

fun nested(n: Int): String {
    try {
        try {
            risky(n)
        } catch (e: MyError) {
            return "inner " + e.code
        } finally {
            print("[fin1]")
        }
    } catch (e: RuntimeException) {
        return "outer " + e.message
    }
    return "none"
}

fun deep(k: Int): Int = if (k == 0) risky(-7) else deep(k - 1) + 1

fun rethrow(): String {
    var s = ""
    try {
        try { s += "T"; throw Exception("x") } catch (x: Exception) { s += "C"; throw x } finally { s += "F" }
    } catch (x: Exception) { return s + x.message }
}

fun finallyThrows(): String {
    try {
        try { return "ret" } finally { print("f1 ") }
    } finally {
        print("f2 ")
        throw RuntimeException("boom2")
    }
}

fun main() {
    println(risky(3))
    try { risky(-1) } catch (e: IllegalArgumentException) { println("caught " + e.message) }
    try { risky(0) } catch (e: Exception) { println("caught " + e.message) }
    val v = try { risky(5) } catch (e: Exception) { 0 }
    println(v)
    val w = try { risky(0) } catch (e: MyError) { e.code } catch (e: Exception) { 1 }
    println(w)
    println(parse("abc").toString() + " " + parse("x"))
    println(nested(1)); println(nested(0)); println(nested(-2))
    try { deep(5) } catch (e: Throwable) { println("deep " + e.message) }
    try { check(1 > 2) { "check msg" } } catch (e: IllegalStateException) { println(e.message) }
    try { require(false) } catch (e: IllegalArgumentException) { println(e.message) }
    try { error("boom") } catch (e: IllegalStateException) { println(e.message) }
    try { throw UnsupportedOperationException() } catch (e: Exception) { println(e.message) }
    var i = 0
    do { i += 3 } while (i < 10)
    println(i)
    var j = 100
    do { j++; if (j % 2 == 1) continue; j += 10 } while (j < 130)
    println(j)
    val sq = Array(5) { it * it }
    println(sq)
    val names = Array<String>(3) { i -> "n" + i }
    println(names[2] + " " + names.size)
    val ia = IntArray(4) { it + 10 }
    println(ia[3])
    println(arrayOf(1, 2, 3).size)
    println(intArrayOf(5, 6, 7)[1])
    val ex = assertFailsWith<IllegalStateException> { error("expected") }
    println(ex.message)
    var count = 0
    for (k in 0 until 10) { try { if (k % 3 == 0) throw RuntimeException("k") ; count++ } catch (e: RuntimeException) { count += 100 } finally { count += 1000 } }
    println(count)
    val zero = sq[0]
    try { println(10 / zero) } catch (e: ArithmeticException) { println(e.message) }
    try { println(ia[7]) } catch (e: IndexOutOfBoundsException) { println(e.message) }
    try { println(sq[-1]) } catch (e: IndexOutOfBoundsException) { println(e.message) }
    try { for (k in 1..5 step zero) print(k) } catch (e: IllegalArgumentException) { println(e.message) }
    println(rethrow())
    try { finallyThrows() } catch (e: RuntimeException) { println("from finally: " + e.message) }
    risky(0)
    println("not reached")
}
