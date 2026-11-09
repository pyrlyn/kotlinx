// Constant-offset field addresses (--target=js folds them into the heap index): Long, Double, Int and Boolean
// fields written in one block and read in others, nested objects and arrays of objects.
class Inner(var a: Long, var b: Int)
class Rec(var x: Long, var y: Double, var n: Int, var ok: Boolean, val inner: Inner)

fun step(r: Rec, i: Int) {
    if (i % 3 == 0) { r.x += i; r.inner.a -= r.x } else { r.y = r.y * 0.5 + i; r.inner.b += i }
    r.n++
    r.ok = r.x > r.inner.a
}

fun main() {
    val rs = Array(8) { Rec(it.toLong() shl 33, it * 1.25, it, false, Inner(-it.toLong(), it)) }
    for (i in 0 until 2000) step(rs[i and 7], i)
    var sx = 0L; var sy = 0.0; var sn = 0; var ok = 0; var sa = 0L; var sb = 0
    for (r in rs) { sx += r.x; sy += r.y; sn += r.n; if (r.ok) ok++; sa += r.inner.a; sb += r.inner.b }
    println("$sx $sy $sn $ok $sa $sb")
}
