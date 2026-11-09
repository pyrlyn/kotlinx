class A(val id: Int) { val xs = ArrayList<A>() }
fun main() { val a = A(1); a.xs.add(a); println(a.xs.size) }
