// Named arguments are evaluated in source order, not in parameter order.
var log = ""
fun t(s: String): String { log += s; return s }
fun f(a: String, b: String, c: String): String = a + b + c
class C(val p: String) { fun g(x: String, y: String): String = p + x + y }
fun mk(): C { log += "r"; return C("P") }

fun main() {
    println(f(c = t("3"), a = t("1"), b = t("2")) + " " + log)
    log = ""
    var x = "c"
    println(f(c = x, b = { x = "a"; "b" }(), a = x))
    println(mk().g(y = t("Y"), x = t("X")) + " " + log)
}
