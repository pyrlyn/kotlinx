// Leak test: break / continue must release the loop body's live references.
class Box(val v: Int)

fun main() {
    var s = 0
    for (i in 0..10) { if (i == 3) continue; if (i == 7) break; s += i }
    var j = 0
    while (true) { val b = Box(j); j++; if (b.v > 5) break; if (b.v % 2 == 0) continue; s += b.v }
    val xs = ArrayList<Box>()
    for (i in 1..3) xs.add(Box(i))
    for (x in xs) { val y = Box(x.v); val z = Box(y.v + 1); if (z.v == 3) continue; s += y.v; if (s > 100) break }
    for (i in 0 until 3) { for (k in 0 until 5) { val q = Box(k); if (q.v == 2) break; s += q.v }; if (i == 1) continue; s += 100 }
    println(s)
}
