// Borrowed field-read locals (`val p = e.f` with no retain/release, see NativeBackend.borrowableFieldLocal):
// the borrowed shapes (LRU unlink / pushFront) and the shapes that must keep RC because the field that holds
// the local's object is overwritten, through the same object or an alias, or by a call. Under ASan (plain RC:
// count zero frees at once) a wrong borrow is a use-after-free.
class Val(val v: Int)
class Node(val id: Int, val value: Val, var prev: Node?, var next: Node?)

class DList(var head: Node?, var tail: Node?) {
    fun unlink(e: Node) {
        val p = e.prev
        val n = e.next
        if (p == null) head = n else p.next = n
        if (n == null) tail = p else n.prev = p
        e.prev = null
        e.next = null
    }
    fun pushFront(e: Node) {
        e.prev = null
        e.next = head
        val h = head
        if (h == null) tail = e else h.prev = e
        head = e
    }
    // `head` is overwritten while `h` is live: h must stay owned (the old head dies here).
    fun dropHead(): Int {
        val h = head
        head = null
        tail = null
        return if (h == null) -1 else h.value.v
    }
    fun ids(): String {
        val sb = StringBuilder()
        var c = head
        while (c != null) { sb.append(c.id); sb.append(' '); c = c.next }
        return sb.toString()
    }
}

// The same field written through an alias of the base (b === a): p must stay owned.
fun aliasStore(a: Node, b: Node): Int {
    val p = a.next
    b.next = null
    val x = if (p == null) -1 else p.value.v
    return x
}

// The field holds the only reference: overwriting it frees the object at once under plain RC, so a
// wrong borrow reads freed memory (ASan).
class Box(var item: Val?, var other: Val?)
fun take(b: Box): Int {
    val v = b.item
    b.item = null
    val r = if (v == null) -1 else v.v
    return r
}
fun takeAlias(a: Box, b: Box): Int {
    val v = a.item
    b.item = null
    val r = if (v == null) -1 else v.v
    return r
}
// Another field: the borrow is safe (a.other is written, a.item still holds v).
fun otherField(a: Box): Int {
    val v = a.item
    a.other = null
    val r = if (v == null) -1 else v.v
    return r
}

fun clear(n: Node) { n.next = null }
// A call between the read and the last use may overwrite the field: p must stay owned.
fun callBetween(a: Node): Int {
    val p = a.next
    clear(a)
    return if (p == null) -1 else p.value.v
}

// A store of the local itself back into the same field keeps the field's reference.
fun storeBack(a: Node, b: Node): Int {
    val p = a.next
    b.next = p
    if (p == null) return -1
    return p.id
}

fun main() {
    val l = DList(null, null)
    for (i in 1..5) l.pushFront(Node(i, Val(i * 10), null, null))
    println(l.ids())
    var c = l.head
    while (c != null) { val nx = c.next; if (c.id % 2 == 0) l.unlink(c); c = nx }
    println(l.ids())
    val t = l.tail!!
    l.unlink(t); l.pushFront(t)
    println(l.ids())
    println(l.dropHead())
    val a = Node(1, Val(1), null, Node(2, Val(42), null, null))
    println(aliasStore(a, a))
    val b = Node(3, Val(3), null, Node(4, Val(43), null, null))
    println(callBetween(b))
    val d = Node(5, Val(5), null, Node(6, Val(44), null, null))
    println(storeBack(d, d))
    println(take(Box(Val(7), null)))
    val bx = Box(Val(8), null)
    println(takeAlias(bx, bx))
    println(otherField(Box(Val(9), Val(10))))
}
