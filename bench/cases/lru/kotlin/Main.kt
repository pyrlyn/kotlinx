// lru: a real-world cyclic structure - an LRU cache (doubly linked list prev<->next + key index).
// Capacity 50k entries, keys 0..200k (skewed LCG stream), 3M get-or-load ops; each entry owns a heap value.
// Every live neighbour pair is a prev<->next cycle; evicted entries and the final list are cyclic garbage.
// Kotlin Native: tracing GC; Kotlin-X compiler: RC + cycle collector; Rust: Rc/Weak; Zig: allocator create/destroy.
@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)
@file:Suppress("DEPRECATION_ERROR")

class Value(val v: Long)
class Entry(val key: Int, val value: Value, var prev: Entry?, var next: Entry?)

// (Kotlin-X compiler subset: class state lives in constructor properties; index is an ArrayList<Entry?>.)
class Lru(val capacity: Int, val index: ArrayList<Entry?>, var head: Entry?, var tail: Entry?, var size: Int) {

    fun unlink(e: Entry) {
        val p = e.prev
        val n = e.next
        if (p == null) head = n else p.next = n
        if (n == null) tail = p else n.prev = p
    }

    fun pushFront(e: Entry) {
        e.prev = null
        e.next = head
        val h = head
        if (h == null) tail = e else h.prev = e
        head = e
    }

    fun get(key: Int): Long {
        val e = index[key]
        if (e != null) {
            if (e.prev != null) { unlink(e); pushFront(e) } // not already at the front
            return e.value.v
        }
        val created = Entry(key, Value(key.toLong() * 7L + 1L), null, null)
        index[key] = created
        pushFront(created)
        size++
        if (size > capacity) {
            val t = tail!!
            unlink(t)
            index[t.key] = null
            size--
        }
        return -1L
    }
}

fun main() {
    val t0 = kotlin.system.getTimeNanos()
    val keys = 200_000
    val index = ArrayList<Entry?>()
    for (k in 0 until keys) index.add(null)
    val cache = Lru(50_000, index, null, null, 0)
    var x = 12345L
    var hits = 0L
    var sum = 0L
    for (i in 0 until 3_000_000) {
        x = (x * 6364136223846793005L + 1442695040888963407L)
        val r = ((x ushr 33) % 1_000_000L).toInt()
        // 80% of requests go to the hot 20% of the key space
        val key = if (r < 800_000) r % 40_000 else 40_000 + r % 160_000
        val v = cache.get(key)
        if (v >= 0L) { hits++; sum += v }
    }
    val checksum = sum + hits * 31L + cache.size
    val t1 = kotlin.system.getTimeNanos()
    println("checksum=$checksum nanos=${t1 - t0}")
}
