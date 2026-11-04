fun apply2(f: (Int) -> Int): Int = f(20)
fun main() {
    val f = fun(y: String): String = "a" + y
    println(f("b"))
    val g = fun(x: Int): Int { if (x > 10) return x * 2; return x }
    println(g(3).toString() + " " + g(30))
    println(apply2(fun(x: Int): Int = x + 1))
    var n = 5
    val h = fun(): Int { n++; return n }
    println(h()); println(n)
    println("O".(fun String.(y: String): String = this + y)("K"))
    secondPart()
}

// `recv.(fnExpr)(args)`: the function expression is evaluated before the receiver.
var order = ""
fun getReceiver(): Int { order += "recv;"; return 1 }
fun getFun(b: Int.(Int) -> Int): Int.(Int) -> Int { order += "fun;"; return b }
// `this` of an extension function inside a plain lambda.
fun String.joiner(): (String) -> String = { a: String -> a + this }
fun secondPart() {
    println(getReceiver().(getFun({ x -> this + x }))(41))
    println(order)
    println("K".joiner()("O"))
    println("K".(fun String.(): (String) -> String = { a: String -> a + this })()("O"))
}
