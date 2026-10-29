// Nullable generic results (null and owned branches), !! on owned temporaries (including a caught
// NullPointerException), Comparable objects compared through compareTo, and string ordering on temporaries.
class Key(val s: String) : Comparable<Key> { override fun compareTo(other: Key): Int = s.compareTo(other.s) }
fun <T> nth(xs: List<T>, i: Int): T? = if (i < xs.size) xs[i] else null
fun <T : Comparable<T>> best(xs: List<T>): T { var b = xs[0]; for (x in xs) if (x > b) b = x; return b }

fun main() {
    var acc = 0
    val keys = ArrayList<Key>()
    for (i in 0 until 100) keys.add(Key("k" + (i * 37 % 100)))
    for (i in 0 until 200) {
        val k = nth(keys, i)
        if (k != null) acc += k.s.length
        acc += best(keys).s.length
        if ("a" + i < "a" + (i + 1)) acc++
        try { acc += nth(keys, i)!!.s.length } catch (e: NullPointerException) { acc += 1000 }
    }
    println(acc)
    println(best(keys).s)
}
