class C(val c: String)
class R(val r: String)
fun use(f: context(C) R.(Int) -> String): String = f(C("c"), R("r"), 1)
fun main() {
    println(use { n -> r + n })
    val g: context(C) R.() -> String = { r + "!" }
    println(g(C("x"), R("y")))
    with(C("w")) { println(g(R("z"))) }
}
