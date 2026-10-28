// BUG: `when` over a String? subject compares string constants against null without a null check -> SIGSEGV.
// Expected: other   Actual: crash (exit -11 / segfault)
// Upstream: box/when/stringOptimization/nullability.kt (found after kotlin.test assert adaptation)
fun foo(x: String?): String {
    when (x) {
        "abc" -> return "abc"
        else -> return "other"
    }
}
fun main() { println(foo(null)) }
