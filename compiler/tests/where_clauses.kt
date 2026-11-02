// Type parameter bounds given in `where` clauses (functions, classes, interfaces).
interface Named { val name: String }
interface Sized { fun size(): Int }

class Item(override val name: String, val n: Int) : Named, Sized {
    override fun size() = n
}

fun <T> describe(x: T): String where T : Named, T : Sized = "${x.name}:${x.size()}"

fun <T, U> pair(a: T, b: U): Int
    where T : Sized,
          U : Sized {
    return a.size() + b.size()
}

class Holder<T>(val x: T) where T : Named, T : Sized {
    fun show() = describe(x)
}

interface Source<T> where T : Named {
    fun get(): T
}

class ItemSource : Source<Item> {
    override fun get() = Item("src", 7)
}

fun main() {
    println(describe(Item("a", 3)))
    println(pair(Item("b", 1), Item("c", 2)))
    println(Holder(Item("d", 4)).show())
    println(ItemSource().get().name)
}
