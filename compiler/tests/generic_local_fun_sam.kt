// Type parameters of a generic function used by its local functions, by lambdas in its body, and generic fun
// interfaces whose type arguments are the called function's own type parameters (SAM conversion of a lambda).
fun interface Mapper<T, R> { fun map(x: T): R }
fun <T, R> apply(value: T, m: Mapper<T, R>): R = m.map(value)
fun <T, R> applyFn(value: T, f: (T) -> R): R = f(value)

fun <T, V : T> pick(x: V): V {
    fun same(y: V) = y
    return same(x)
}

fun <T> firstOf(vararg xs: T): T {
    fun head(list: List<T>): T = list[0]
    return head(listOf(xs[0]))
}

fun <T> cast(a: String): T = applyFn(a) { it as T }
fun <T> listOfTwo(a: T): List<T> = applyFn(a) { x -> listOf<T>(x, x) }

fun main() {
    println(pick<Any, String>("pick"))
    println(firstOf(3, 4))
    println(apply(20) { it + 1 })
    println(apply("abc") { it.length })
    println(apply(2) { "n=$it" })
    println(cast<String>("cast"))
    println(listOfTwo(7))
    println(listOfTwo("s"))
}
