class Box(val tag: String) {
    fun <T> wrap(x: T, f: (T) -> String) = tag + f(x)
    fun <T> pick(a: T, b: T) = if (tag.length > 1) a else b
}

fun main() {
    val b = Box("id:")
    println(b.wrap(42) { "n$it" })
    println(b.pick("yes", "no"))
    println(b.pick(1, 2) + 10)
}
