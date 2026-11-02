fun <T> materialize(x: Any): T = x as T
fun <T, R> apply1(v: T, f: (T) -> R): R = f(v)
fun <T> twice(x: Any): T = apply1(x) { materialize(it) }
fun pick(b: Boolean): String = if (b) materialize("yes") else "no"
fun main() {
    val s: String = materialize("hi")
    val n: Int = materialize(41)
    println(s); println(n + 1)
    println(twice<String>("ok"))
    println(pick(true)); println(pick(false))
}
