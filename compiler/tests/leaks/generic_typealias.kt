// Generic type aliases: substituted at each use in types, and as constructors (explicit or inferred arguments).
class Cell<T>(val value: T)
typealias AliasedCell<TT> = Cell<TT>
typealias Pairs<K> = Pair<K, String>
typealias Ints<T> = List<Int>
typealias Mapper<A, B> = (A) -> B

fun describe(c: AliasedCell<Int>): String = "cell " + c.value
fun first(p: Pairs<Int>): Int = p.first
fun count(xs: Ints<String>): Int = xs.size
fun <A, B> apply(f: Mapper<A, B>, a: A): B = f(a)

fun main() {
    println(describe(AliasedCell(7)))
    println(describe(AliasedCell<Int>(8)))
    println(first(Pair(3, "x")))
    println(count(listOf(1, 2, 3)))
    println(apply({ x: Int -> x * 2 }, 21))
    val m: Mapper<String, Int> = { it.length }
    println(m("four"))
}
