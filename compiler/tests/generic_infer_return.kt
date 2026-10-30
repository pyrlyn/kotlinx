// Return types of expression-bodied generic functions are inferred per instance.
fun <T> eval(fn: () -> T) = fn()
fun <T> first(xs: List<T>) = xs[0]
fun <A, B> pair(a: A, b: B) = Pair(a, b)
fun <T> say(x: T) = println(x)
fun main() {
    println(eval { 42 } + 1)
    println(eval { "s" } + "!")
    println(first(listOf("a", "b")))
    val p = pair(1, "x"); println(p.first + 1)
    say(3)
}
