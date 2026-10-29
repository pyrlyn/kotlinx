// `+=` of strings and lists into lists in a loop, with asserts on the results: all released.
fun main() {
    var total = 0
    for (r in 0 until 200) {
        val names = mutableListOf<String>()
        for (i in 0 until 10) names += "n" + i
        val all = ArrayList<String>()
        all += names
        all += listOf("x" + r, "y")
        assertEquals(12, all.size)
        assertTrue(all[11] == "y")
        total += all.size
    }
    println(total)
}
