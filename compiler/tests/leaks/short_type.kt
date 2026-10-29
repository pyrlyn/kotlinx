// Short values in lists and strings in a loop: all released.
fun main() {
    var n = 0
    for (i in 0 until 300) {
        val l = arrayListOf<Short>((i * 300).toShort(), Short.MAX_VALUE)
        n += ("" + l[0] + l).length
    }
    println(n)
}
