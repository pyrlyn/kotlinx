val String.shout: String get() = this + "!"
fun String.twice() = this + this

fun apply1(f: (String) -> String, s: String) = f(s)

fun main() {
    val len = "kotlin"::length
    println(len())
    val at = "KOTLIN"::get
    println("${at(1)}${at(0)}")
    println(apply1(String::plus.let { p -> { s: String -> p(s, "c") } }, "ab"))
    println(apply1(String::shout, "hey"))
    println(apply1(String::twice, "ab"))
    val ts = Int::toString
    println(ts(42) + "?")
    val add = 10::plus
    println(add(5))
}
