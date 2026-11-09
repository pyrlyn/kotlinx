class Box<T>(var v: T)
class A(val n: Int) { var b: Box<A>? = null }
fun main() { val a = A(4); a.b = Box(a); println(a.b!!.v.n) }
