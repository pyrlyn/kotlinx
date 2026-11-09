class A(val n: Int) { var f: (() -> Int)? = null }
fun main() { val a = A(3); a.f = { a.n * 2 }; println(a.f!!()) }
