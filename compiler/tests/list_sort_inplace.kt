// ArrayList.sort()/sortDescending() in place, and an if/else whose branches are a
// discarded reference (removeAt) and Unit.
fun main() {
    val xs = ArrayList<Int>()
    for (v in listOf(5, 3, 9, 1, 3, 7)) xs.add(v)
    xs.sort(); println(xs)
    xs.sortDescending(); println(xs)
    val ss = ArrayList<String>()
    ss.add("pear"); ss.add("apple"); ss.add("fig")
    ss.sort(); println(ss)
    val ds = ArrayList<Double>(); ds.add(2.5); ds.add(-1.0); ds.add(0.0)
    ds.sort(); println(ds)
    var i = 0
    while (i < ss.size) { if (ss[i].length < 4) ss.removeAt(i) else i++ }
    println(ss)
    val sb = StringBuilder()
    for (k in 0..3) { if (k % 2 == 0) sb.append(k) else println("odd " + k) }
    println(sb.toString())
}
