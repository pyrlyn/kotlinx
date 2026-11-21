// 32-bit collection counts and lengths on --target=js: growth, rehash, shared references and long builders.
fun main() {
    val l = mutableListOf<Int>()
    for (i in 0 until 200000) l.add(i)
    val shared = l
    var s = 0L
    for (x in shared) s += x
    println("${l.size} $s ${l.last()}")
    val m = HashMap<Int, Int>()
    for (i in 0 until 50000) m[i] = i * 3
    for (i in 0 until 50000 step 2) m.remove(i)
    println("${m.size} ${m[49999]} ${m.containsKey(2)}")
    val sb = StringBuilder()
    for (i in 0 until 30000) sb.append(i % 10)
    val str = sb.toString()
    println("${str.length} ${str.substring(29990)}")
    val lists = List(1000) { l }
    var t = 0
    for (x in lists) t += x.size
    println(t)
}
