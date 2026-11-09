class A(val n: Int) { val items = ArrayList<Any>() }
fun main() { val a = A(5); a.items.add(a); println(a.items.size) }
