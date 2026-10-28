// recursion: naive fib(32) + deep non-tail recursion depth 100_000.
@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)
@file:Suppress("DEPRECATION_ERROR")

fun fib(n: Long): Long = if (n < 2) n else fib(n - 1) + fib(n - 2)
fun deep(n: Long): Long = if (n == 0L) 0L else n + deep(n - 1)

fun main(args: Array<String>) {
    val n = 32L + args.size // args.size == 0; keeps n opaque to the optimizer
    val d = 100_000L + args.size
    val t0 = kotlin.system.getTimeNanos()
    val checksum = fib(n) + deep(d)
    val t1 = kotlin.system.getTimeNanos()
    println("checksum=$checksum nanos=${t1 - t0}")
}
