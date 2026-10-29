// Removing strings from lists via remove/-=/clear in a loop: removed elements released.
fun main() {
    var n = 0
    for (r in 0 until 300) {
        val l = ArrayList<String>()
        for (i in 0 until 8) l += "s" + i
        l -= "s3"
        l.remove("s5")
        if ("s1" in l) n += l.size
        n += l.indexOf("s7")
        if (r % 2 == 0) l.clear()
    }
    println(n)
}
