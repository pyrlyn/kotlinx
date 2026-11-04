// Insertion-ordered maps: iteration, keys/values, remove/clear/isEmpty, mapOf/mutableMapOf pairs, LinkedHashMap.
class V(val n: Int)
fun main() {
    val m = HashMap<String, Int>()
    for (k in listOf("z","b","q","a","m","c","y","d","x","e","w","f","v","g","u","h","t","i")) m[k] = 1
    m.remove("q"); m["q"] = 2
    for ((k, v) in m) print(k + v)
    println()
    val n = mutableMapOf(50 to "a", 3 to "b", 17 to "c")
    n.remove(3)
    for (e in n) print("${e.key}=${e.value} ")
    println(n.keys); println(n.values); println(n.isEmpty())
    val o = LinkedHashMap<Int, V>()
    for (i in 0 until 100) o[i] = V(i)
    for (i in 0 until 100) if (i % 3 != 0) { val r = o.remove(i); if (r != null && r.n != i) println("bad") }
    var s = 0
    for ((k, v) in o) s += k + v.n
    println(o.size.toString() + " " + s)
    o.clear(); println(o.isEmpty())
    val mm = mapOf("x" to V(1), "y" to V(2))
    println(mm["y"]!!.n)
}
