class Outer(val s: String) {
    class Nested(val n: Int) { fun show() = "nested $n" }
    inner class Inner(val t: String) { fun show() = s + t }
}

fun main() {
    val mk = Outer::Nested
    println(mk(3).show())
    val inner = Outer::Inner
    val o = Outer("O")
    println(inner(o, "K").show())
    val bound = o::Inner
    println(bound("!").show())
}
