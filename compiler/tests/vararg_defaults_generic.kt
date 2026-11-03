fun foo(vararg arr: Int = intArrayOf(1, 2)): String { var s = ""; for (x in arr) s += x; return s }
fun <T> test3(vararg p: T): String { var s = ""; for (x in p) s += x; return s }

fun main() {
    println(foo() + "|" + foo(42))
    println(test3<String>() + "|" + test3("a", "b"))
}
