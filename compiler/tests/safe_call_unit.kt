class C(val n: Int) { fun method() { println("method " + n) } }
fun log(x: String) { println("log " + x) }
fun main() {
    val a: C? = null
    val b: C? = C(1)
    a?.method() ?: log("a null")
    b?.method() ?: log("b null")
    a?.method()
    val u = b?.method()
    println(u == null)
    println(a?.method() == null)
}
