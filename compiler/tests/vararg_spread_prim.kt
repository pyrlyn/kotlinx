fun test1(vararg p: Int): String { var s = ""; for (x in p) s += x; return s }
fun sum(prefix: String, vararg xs: Long): String { var t = 0L; for (x in xs) t += x; return prefix + t }

fun main() {
    println(test1(*intArrayOf()) + "|" + test1(*intArrayOf(1, 2)) + "|" + test1(p = *intArrayOf(3)))
    val a = longArrayOf(4L, 5L)
    println(sum("s", *a))
    println(test1(1, 2, 3))
}
