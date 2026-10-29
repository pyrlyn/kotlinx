@file:Suppress("UNUSED")
annotation class Tag(val v: String)

@Tag("cls") class P(@Tag("p") val x: Int, @Volatile var y: Int) {
    @Suppress("A", "B") fun sum(): Int = x + y
    @get:Tag("g") val twice: Int get() = x * 2
}

@Target(AnnotationTarget.FUNCTION)
annotation class Mark

@Mark
fun f(@Tag(")") a: Int): Int {
    @Suppress("NAME_SHADOWING") val a2 = a + 1
    return a2
}

fun main() {
    val p = P(2, 3)
    println(p.sum())
    println(p.twice)
    println(f(4))
    val xs = listOf(1, 2, 3)
    println(xs.map { @Suppress("X") it * 2 })
}
