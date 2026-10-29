// Lazily created objects holding collections and strings: created on first access inside a loop, mutated
// through repeated accesses, one never accessed (never created), all released at exit.
object Cache { val items = ArrayList<String>(); var hits = 0 }
object Unused { val big = arrayListOf("never", "created") }
class Reg { companion object { val names = ArrayList<String>() } }

fun touch(i: Int): Int { Cache.items.add("v" + i); Cache.hits++; return Cache.items.size }

fun main() {
    var acc = 0
    for (i in 0 until 300) {
        acc += touch(i)
        if (i % 3 == 0) Reg.names.add("r" + i)
    }
    println(acc)
    println(Cache.hits)
    println(Reg.names.size)
}
