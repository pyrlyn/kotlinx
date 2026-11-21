class A(val id: Int) { val m = HashMap<String, A>() }
fun main() { val a = A(1); a.m["self"] = a; println(a.m.size) }
