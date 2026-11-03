fun interface G<T> {
    fun foo(t: T): T
}

fun interface C : G<Char>

fun interface GenericToAny<T> {
    fun invoke(t: T): String
}

fun interface IntToStr : GenericToAny<Int>

fun useC(c: C): Char = c.foo('a')
fun useG(g: G<Char>): Char = g.foo('x')
fun show(f: IntToStr) = f.invoke(41)

fun main() {
    val c = C { it + 2 }
    println(c.foo('A'))
    println(useC { it + 1 })
    println(useG(c))
    println(show { "n=${it + 1}" })
}
