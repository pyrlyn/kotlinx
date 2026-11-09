class N(val id: Int) { var next: N? = null }
fun main() { val a = N(1); val b = N(2); a.next = b; b.next = a; println(a.next!!.next!!.id) }
