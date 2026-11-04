// HashMap with String keys: content hashing/equality, the map retains its keys.
fun countWords(text: String): HashMap<String, Int> {
    val m = HashMap<String, Int>()
    var w = ""
    for (c in text + " ") {
        if (c == ' ') {
            if (w.length > 0) { m[w] = m.getOrDefault(w, 0) + 1; w = "" }
        } else w = w + c
    }
    return m
}
fun main() {
    val m = countWords("a bb a ccc bb a")
    println(m.size)
    println(m["a"])
    println(m.get("bb"))
    println(m.containsKey("ccc"))
    println(m.containsKey("zz"))
    println(m.getOrDefault("zz", -1))
    val k = "c" + "cc"
    m[k] = 10
    println(m["ccc"])
    val big = HashMap<String, Long>()
    for (i in 0 until 1000) big.put("key" + i, i.toLong() * 3L)
    var s = 0L
    for (i in 0 until 1000) s += big["key" + i]!!
    println(big.size)
    println(s)
    val h = hashMapOf<String, Double>()
    h["pi"] = 3.5
    println(h["pi"])
}
