// Empty listOf()/mutableMapOf() typed by the expected type (declared variable, defaulted parameter).
class C(val t: List<String> = listOf())
fun f(x: List<Int> = listOf()) = x.size
fun main() { val x: List<String> = listOf(); println(x.size); println(C().t.size); println(f()) }
