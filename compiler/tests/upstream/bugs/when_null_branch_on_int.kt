// BUG: `null` branch in `when` over a non-null Int produces invalid LLVM IR
// ("'%t1' defined with type 'i32' but expected 'ptr'"); clang fails.
// Expected: compiles (kotlinc warns, branch never matches), prints 1
// Upstream: box/when/kt2457.kt
fun foo(i: Int): Int = when (i) {
    1 -> 1
    null -> 2
    else -> 3
}
fun main() { println(foo(1)) }
