val greeting = "hi"
val computed: String get() = "got"
var counter = 1

fun call(f: () -> String): String = f()

fun main() {
    println(call(::greeting))
    println(call(::computed))
    val c = ::counter
    counter = 5
    println(c() + 1)
    var acc = "a"
    fun grow() { acc += "b" }
    val g = ::grow
    g(); g()
    println(acc)
}
