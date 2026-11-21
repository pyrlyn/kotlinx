interface I { fun id(): Int }
class A(val n: Int) : I { var other: I? = null; override fun id() = n }
fun main() { val a = A(1); a.other = a; println(a.other!!.id()) }
