// file: read data/words.txt, count lines and words, checksum h = h*31 + byte (wrapping Long).
@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)
@file:Suppress("DEPRECATION_ERROR")

import kotlinx.cinterop.*
import platform.posix.*

fun main() {
    val path = getenv("BENCH_DATA")?.toKString() ?: "data/words.txt"
    val t0 = kotlin.system.getTimeNanos()
    val f = fopen(path, "rb") ?: error("open failed: $path")
    fseek(f, 0, SEEK_END)
    val size = ftell(f).toInt()
    fseek(f, 0, SEEK_SET)
    val buf = ByteArray(size)
    val got = buf.usePinned { fread(it.addressOf(0), 1u, size.toULong(), f) }
    fclose(f)
    check(got.toInt() == size) { "short read" }
    var lines = 0L; var words = 0L; var h = 0L; var inw = false
    for (sb in buf) {
        val b = sb.toInt() and 0xFF
        h = h * 31 + b
        if (b == 10) lines++
        val ws = b == 32 || b == 10
        if (!ws && !inw) words++
        inw = !ws
    }
    val checksum = h + lines * 1_000_003 + words
    val t1 = kotlin.system.getTimeNanos()
    println("checksum=$checksum lines=$lines words=$words nanos=${t1 - t0}")
}
