// vararg parameters: packing, empty, spread, named, generic, methods.
fun sum(vararg xs: Int): Int { var s = 0; for (x in xs) s += x; return s }
fun join(sep: String, vararg parts: String): String { var r = ""; for (i in 0 until parts.size) { if (i > 0) r += sep; r += parts[i] }; return r }
fun <T> count(vararg items: T): Int = items.size
class Acc { fun add(vararg ns: Long): Long { var s = 0L; for (n in ns) s += n; return s } }
fun main() {
    println(sum())
    println(sum(1, 2, 3))
    val l = listOf(4, 5)
    println(sum(*l))
    println(join("-", "a", "b", "c"))
    println(join(sep = "+", parts = *arrayOf("x", "y")))
    println(count("p", "q"))
    println(Acc().add(1L, 2L, 3L))
}
