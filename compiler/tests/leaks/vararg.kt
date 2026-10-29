// vararg string lists packed per call and spread from lists in a loop: all released.
fun cat(vararg ps: String): String { var r = ""; for (p in ps) r += p; return r }
fun main() {
    var n = 0
    for (i in 0 until 300) {
        n += cat("a" + i, "b", "c").length
        val l = arrayListOf("x", "y" + i)
        n += cat(*l).length + cat().length
    }
    println(n)
}
