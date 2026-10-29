// Extensions: receivers passed (owned temporaries and locals) to top-level extension functions and properties,
// extensions returning new objects, generic extensions on lists, extensions on interfaces, and class getters.
class Node(val v: Int, val next: Node?)
interface Named { fun name(): String }
class Item(val id: Int) : Named { override fun name(): String = "item" + id }
class Bag(val items: ArrayList<Item>) {
    val first: Item get() = items[0]
    val label: String get() = "bag of " + items.size
}

fun Node.prepend(v: Int): Node = Node(v, this)
fun Node.length(): Int { var n = 1; var c = next; while (c != null) { n++; c = c.next }; return n }
fun Named.shout(): String = name() + "!"
fun <T> List<T>.lastOr(d: T): T = if (size == 0) d else this[size - 1]
fun String.wrap(): String = "[" + this + "]"
val Item.tag: String get() = name().wrap()
fun ArrayList<Item>.ids(): String { val sb = StringBuilder(); for (i in this) sb.append(i.id); return sb.toString() }

fun main() {
    var total = 0
    for (k in 0 until 300) {
        var n = Node(0, null)
        for (j in 1..5) n = n.prepend(j)
        total += n.length() + Node(k, null).prepend(1).length()
        val items = ArrayList<Item>()
        for (j in 0 until 3) items.add(Item(k + j))
        val bag = Bag(items)
        total += bag.first.shout().length + bag.label.length + Item(k).tag.length
        total += items.lastOr(Item(-1)).id + ArrayList<Item>().lastOr(Item(-2)).id
        total += bag.items.ids().length + "x".wrap().wrap().length
        val nm: Named = Item(k)
        total += nm.shout().length
    }
    println(total)
}
