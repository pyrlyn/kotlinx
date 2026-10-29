// withIndex lists and raw-string templates built in a loop: all released.
fun main() {
    var n = 0
    for (r in 0 until 300) {
        val xs = arrayListOf("a" + r, "b")
        for ([i, x] in xs.withIndex()) n += i + x.length
        val s = """
            v=$r
        """.length
        n += s
    }
    println(n)
}
