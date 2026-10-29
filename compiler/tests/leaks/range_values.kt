// Ranges as values: progression objects created by operators, stored in lists and fields, iterated,
// converted to strings and lists, and temporaries in `in` / `step` chains.
class Window(val r: IntRange, var p: IntProgression)

fun pick(k: Int): IntProgression = if (k % 2 == 0) 0..k else k downTo 0

fun main() {
    var total = 0L
    val keep = ArrayList<IntProgression>()
    for (k in 0 until 300) {
        val r = 0..k
        val w = Window(r, r step 2)
        w.p = w.p.reversed()
        for (i in w.p) total += i
        if (k in w.r) total++
        if (k / 2 in pick(k) step 3) total += 2
        total += pick(k).toList().size
        total += ("" + (r step 5)).length
        for (c in 'a'..'z' step k % 5 + 1) total += c.code
        for (x in (1L..k.toLong()).reversed()) total += x
        if (k % 50 == 0) keep.add(w.p)
    }
    for (p in keep) total += p.count()
    println(total)
}
