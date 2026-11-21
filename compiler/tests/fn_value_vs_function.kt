// A function wins over a property of function type with the same name when it takes the arguments; otherwise the
// property is invoked (a function with only context parameters takes none explicitly).
val b: (String) -> String = { "property b" }
val c: context(String) () -> String = { "property c" }
val d: (Int) -> String = { "property d" }
fun d(x: Int): String = "fun d($x)"

fun localWins(): String {
    fun b(a: String): String = "local b($a)"
    return b("1")
}

fun propertyWins(): String {
    context(a: String)
    fun c(): String = "local c"
    return c("1")
}

fun main() {
    println(localWins())
    println(propertyWins())
    // The local function above is not visible here.
    println(b("x"))
    println(d(2))
    println(d.invoke(3))
}
