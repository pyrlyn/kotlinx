// Untyped top-level properties are visible to functions with inferred return types.
val log = StringBuilder()
fun logged(v: Int) = v.also { log.append("x") }
fun main() { println(logged(3)); println(log.toString()) }
