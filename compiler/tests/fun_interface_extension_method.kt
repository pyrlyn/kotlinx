fun interface Fmt {
    fun Int.render(suffix: String): String
}

fun Int.dashed(s: String) = "$this-$s"

fun main() {
    val a = Fmt { s -> "<" + this + s + ">" }
    val b = Fmt(Int::dashed)
    with(a) { println(1.render("x")) }
    with(b) { println(2.render("y")) }
}
