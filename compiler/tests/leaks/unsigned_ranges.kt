// Unsigned progression objects created, stepped and converted to lists in a loop: all released.
fun main() {
    var n = 0
    for (r in 0 until 300) {
        val p = (1u..(r.toUInt() + 5u)) step 2
        n += p.toList().size + p.count()
        if ((r.toUInt()) in p) n++
        n += (10uL downTo 1uL step 3L).toString().length
    }
    println(n)
}
