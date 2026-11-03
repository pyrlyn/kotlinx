// HashMap.getOrDefault with reference values: found values are shared, defaults owned.
class Box(val n: Int)

fun main() {
    val m = HashMap<Int, String>()
    m.put(1, "one")
    println(m.getOrDefault(1, "none"))
    println(m.getOrDefault(2, "no" + "ne"))
    val b = HashMap<String, Box>()
    b.put("a", Box(7))
    val x = b.getOrDefault("a", Box(0))
    val y = b.getOrDefault("z", Box(-1))
    println(x.n + 100 * y.n)
    var total = 0
    for (i in 0..3) total += b.getOrDefault(if (i % 2 == 0) "a" else "q", Box(i)).n
    println(total)
}
