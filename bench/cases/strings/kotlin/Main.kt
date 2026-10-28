// strings: build a 2M-number CSV-like text with StringBuilder, then parse it char by char.
@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)
@file:Suppress("DEPRECATION_ERROR")

fun main() {
    val n = 2_000_000
    val t0 = kotlin.system.getTimeNanos()
    val sb = StringBuilder()
    var i = 0
    while (i < n) {
        sb.append(i)
        sb.append(',')
        if (i % 10 == 9) sb.append("x\n")
        i++
    }
    val s = sb.toString()
    var sum = 0L
    var cur = 0L
    var fields = 0L
    var lines = 0L
    for (c in s) {
        if (c == ',') { sum += cur; cur = 0L; fields++ }
        else if (c == '\n') lines++
        else if (c >= '0' && c <= '9') cur = cur * 10 + (c - '0')
    }
    val checksum = sum + fields * 7 + lines * 13 + s.length
    val t1 = kotlin.system.getTimeNanos()
    println("checksum=$checksum nanos=${t1 - t0}")
}
