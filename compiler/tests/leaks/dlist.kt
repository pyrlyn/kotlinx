// Leak test: doubly linked list (LRU-style) with prev<->next cycles: front moves, unlinks, evictions
// that keep stale prev/next pointers, nodes owning immutable payloads, and a final cyclic list.
class Payload(val v: Long, val tag: String)
class Cell(val key: Int, val payload: Payload, var prev: Cell?, var next: Cell?)
class DList(var head: Cell?, var tail: Cell?, var size: Int)

fun unlink(l: DList, e: Cell) {
    val p = e.prev
    val n = e.next
    if (p == null) l.head = n else p.next = n
    if (n == null) l.tail = p else n.prev = p
}

fun pushFront(l: DList, e: Cell) {
    e.prev = null
    e.next = l.head
    val h = l.head
    if (h == null) l.tail = e else h.prev = e
    l.head = e
}

fun main() {
    val l = DList(null, null, 0)
    val index = ArrayList<Cell?>()
    for (k in 0 until 64) index.add(null)
    var sum = 0L
    var x = 7L
    for (i in 0 until 20000) {
        x = x * 6364136223846793005L + 1442695040888963407L
        val key = ((x ushr 33) % 64L).toInt()
        val e = index[key]
        if (e != null) {
            if (e.prev != null) { unlink(l, e); pushFront(l, e) }
            sum += e.payload.v
        } else {
            val c = Cell(key, Payload(key.toLong() * 3L, "k"), null, null)
            index[key] = c
            pushFront(l, c)
            l.size++
            if (l.size > 16) {
                val t = l.tail!!
                unlink(l, t)
                index[t.key] = null
                l.size--
            }
        }
    }
    var n = 0
    var cur = l.head
    while (cur != null) { n++; cur = cur.next }
    println("sum=$sum size=${l.size} walked=$n")
}
