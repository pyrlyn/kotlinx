class Fmt(val prefix: String) {
    fun String.tag(n: Int): String = prefix + this + n
    fun Int.twice() = this * 2
    fun run(): String = "ab".tag(3.twice()) + " " + "c".tag(1)
}
fun main() {
    val f = Fmt("<")
    println(f.run())
    println(Fmt("#").run())
}
