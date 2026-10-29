// Enums: entries referenced from objects and lists, enum-valued var fields reassigned, entries list iterated,
// list literals of objects; entries are immortal globals, everything else must be freed.
enum class Kind(val weight: Int) { SMALL(1), LARGE(10) }

class Item(val kind: Kind, var next: Item?) { var state = Kind.SMALL }

fun main() {
    var sum = 0L
    var head: Item? = null
    for (i in 0 until 1000) {
        val k = if (i % 3 == 0) Kind.LARGE else Kind.SMALL
        val it = Item(k, head)
        it.state = Kind.LARGE
        if (i % 2 == 0) head = it
        for (e in Kind.values()) sum += e.weight
        val l = listOf(Item(k, null), Item(Kind.SMALL, null))
        sum += l[0].kind.weight + l.size
        sum += ("" + k).length
    }
    println(sum)
}
