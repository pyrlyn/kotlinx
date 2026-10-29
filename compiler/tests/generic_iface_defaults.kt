interface Container<T> {
    fun items(): List<T>
    fun firstOr(d: T): T { val xs = items(); return if (xs.isEmpty()) d else xs[0] }
    fun count(): Int = items().size
    fun describe(): String = "Container(" + count() + ")"
}

class Ints(val xs: List<Int>) : Container<Int> {
    override fun items(): List<Int> = xs
}

class Names : Container<String> {
    override fun items(): List<String> = listOf("a", "b")
    override fun describe(): String = "Names"
}

class Box<E>(val e: E) : Container<E> {
    override fun items(): List<E> = listOf(e)
}

fun main() {
    val i = Ints(listOf(4, 5))
    println("${i.firstOr(0)} ${i.count()} ${i.describe()}")
    println("${Ints(listOf<Int>()).firstOr(-1)}")
    val n = Names()
    println("${n.firstOr("z")} ${n.describe()}")
    val c: Container<String> = n
    println(c.count())
    val b = Box(3.5)
    println("${b.firstOr(0.0)} ${b.describe()}")
}
