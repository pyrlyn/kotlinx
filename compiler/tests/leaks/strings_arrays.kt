// Leak test: strings and primitive arrays are reference counted and freed (templates, concatenation,
// toString, fields, lists, map{}, when/if values, comparisons, returns, getenv/toKString).
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
import kotlinx.cinterop.toKString
import platform.posix.getenv

class Named(val name: String, val data: IntArray)

fun label(i: Int): String = if (i % 2 == 0) "even$i" else "odd" + i

fun kind(s: String): Int = when (s) {
    "even0" -> 1
    "odd1" -> 2
    else -> 3
}

fun fill(n: Int): LongArray {
    val a = LongArray(n)
    for (i in 0 until n) a[i] = i.toLong() * 3
    return a
}

fun main() {
    var total = 0L
    val names = ArrayList<Named>()
    for (i in 0 until 500) {
        val s = label(i)
        total += s.length.toLong() + kind(s).toLong()
        val t = "item-$i-${s.length}"
        if (t == "item-3-4") total += 100L
        val arr = IntArray(8)
        arr[0] = i
        if (i % 50 == 0) names.add(Named(t, arr))
        val la = fill(16)
        total += la[15] + i.toString().length.toLong()
        val sb = StringBuilder()
        sb.append(s)
        sb.append(i)
        total += sb.toString().length.toLong()
    }
    val lens = names.map { it.name.length.toLong() + it.data[0].toLong() }
    total += lens.sumOf { it }
    val home = getenv("NO_SUCH_VARIABLE_FOR_TEST")?.toKString()
    if (home == null) total += 1L
    val bytes = ByteArray(4)
    bytes[1] = 7
    total += bytes[1].toLong() + bytes.size.toLong()
    println(total)
}
