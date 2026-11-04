// Regression: a line starting with `.` / `?.` continues the expression (class bodies, top level, functions).
val top = listOf(1, 2, 3)
    .filter { it > 1 }
    .size
class C(val xs: List<Int>) {
    val total = xs
        .map { it * 2 }
        .size
    val first: Int? = xs
        .firstOrNull()
    fun f(): Int {
        return xs
            .filter { it > 1 }
            .size
    }
}
fun main() {
    val c = C(listOf(1, 2, 3))
    println(top)
    println(c.total)
    println(c.first)
    println(c.f())
    val s: String? = "abc"
    println(s
        ?.length)
}
