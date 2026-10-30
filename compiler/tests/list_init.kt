// Array(n) { init }, List(n) { }, MutableList(n) { }.
fun main() { val a = MutableList(3) { it * 2 }; println(a[2]); val b = List(2) { "x$it" }; println(b[1]); val c = Array(2) { i -> Array(2) { j -> i * 10 + j } }; println(c[1][1]); val d = Array<Int>(3, { it }); println(d.size) }
