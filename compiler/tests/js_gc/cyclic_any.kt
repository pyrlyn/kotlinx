class A(val id: Int) { var x: Any? = null }
fun main() { val a = A(1); a.x = a; println(a.id) }
