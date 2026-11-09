// JsGen variable reuse (--target=js): phi swaps in loops, Long (two-part) values, booleans and doubles that are
// live across calls and branches, and many short-lived temporaries.
fun mix(a: Long, b: Long, c: Int): Long = (a xor (b shl 7)) * 31 + c

fun fib(n: Int): Long {
    var a = 0L; var b = 1L
    for (i in 0 until n) { val t = a; a = b; b = t + b }
    return a
}

fun rotate(n: Int): String {
    var x = 1; var y = 2; var z = 3
    for (i in 0 until n) { val t = x; x = y; y = z; z = t }
    return "$x$y$z"
}

fun collatz(start: Long): Int {
    var n = start; var steps = 0
    while (n != 1L) { n = if (n % 2L == 0L) n / 2 else 3 * n + 1; steps++ }
    return steps
}

fun main() {
    println(fib(90))
    println(rotate(100) + " " + rotate(101) + " " + rotate(102))
    var h = 17L; var d = 0.5; var flag = false
    for (i in 0 until 1000) {
        val a = mix(h, i.toLong(), i); val b = mix(a, h, -i); val c = a - b
        val big = i > 500
        if (big != flag) { d = d * 1.5 + c % 7; flag = big }
        h = if (c > 0) c xor h else h - c
    }
    println("$h $d $flag")
    println(collatz(27) + collatz(97) + collatz(871))
    var s = 0L
    for (i in 1..30) for (j in 1..30) s += mix(i.toLong(), j.toLong(), i * j) % 1000
    println(s)
}
