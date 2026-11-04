class P(val x: Int) { fun twice(): Int = x * 2 }
fun main() {
    val e = fun String.(y: String): String = this + y
    println("O".e("K"))
    val inc: Int.(Int) -> Int = { this + it }
    println(5.inc(3))
    val tw: P.() -> Int = { x + 100 }
    println(P(1).tw())
    val twice: P.() -> Int = { 0 }
    println(P(4).twice())
}
