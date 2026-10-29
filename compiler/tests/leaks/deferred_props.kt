// lateinit and init-assigned reference properties, reassigned in a loop: old values and objects released.
class H(val i: Int) {
    lateinit var s: String
    val l: ArrayList<String>
    init { l = arrayListOf("a" + i) }
}
fun main() {
    var n = 0
    for (i in 0 until 300) { val h = H(i); h.s = "x" + i; h.s = "y" + i; n += h.s.length + h.l.size }
    println(n)
}
