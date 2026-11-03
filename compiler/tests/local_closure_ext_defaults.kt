fun main() {
    var seed = 0
    val base = "b"
    fun Int.add() { seed += this }
    fun greet(n: String = base, p: String = "!"): String = "hi " + n + p + seed
    3.add(); 4.add()
    println(greet())
    println(greet("x"))
    println(greet("y", "?"))
}
