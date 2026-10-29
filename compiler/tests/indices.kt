fun main() {
    val l = arrayListOf(10, 20, 30)
    for (i in l.indices) print("${l[i]} ")
    println(l.lastIndex)
    val s = "abcd"
    for (i in s.indices.reversed()) print(s[i])
    println(" ${s.lastIndex}")
    val a = IntArray(4)
    println("${a.indices} ${a.lastIndex}")
    val e = ArrayList<String>()
    println("${e.indices.isEmpty()} ${e.lastIndex} ${2 in l.indices} ${3 in l.indices}")
}
