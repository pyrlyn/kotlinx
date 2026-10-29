fun sum(r: IntProgression): Int { var t = 0; for (i in r) t += i; return t }

fun main() {
    val r = 1..10
    println(sum(r))
    println(r.first.toString() + " " + r.last + " " + r.step + " " + r.count())
    val p = r step 3
    println(p.last)
    for (i in p) print(i)
    println()
    for (i in r.reversed() step 4) print(" " + i)
    println()
    val d = 10 downTo 1 step 3
    println(d.toList())
    val u = 0 until 5
    println(u.last.toString() + " " + u.isEmpty() + " " + (5 until 5).isEmpty())
    println(5 in r)
    println(11 in r)
    println(12 !in r)
    val x = 7
    println(x in 1..7)
    println(x in 1 until 7)
    println(x in 10 downTo 5)
    println(4 in p)
    println(5 in p)
    val lr = 1L..3000000000L step 1000000000L
    println(lr.toList())
    val cr = 'a'..'e'
    for (c in cr step 2) print(c)
    println()
    println('c' in cr)
    println(('z' downTo 'w').toList())
    val e = 5..1
    var n = 0
    for (i in e) n++
    println(n)
    val big = 2147483640..2147483647
    var cnt = 0
    for (i in big) cnt++
    println(cnt)
    val ranges = ArrayList<IntRange>()
    ranges.add(1..2); ranges.add(3..9)
    var s = 0
    for (rg in ranges) s += rg.endInclusive - rg.start
    println(s)
    for (i in r) { if (i % 2 == 0) continue; if (i > 7) break; print(i) }
    println()
    println(sum(0..100 step 25))
    println((1..3).toList() == listOf(1, 2, 3))
    println((1..3).toList() != listOf(1, 2))
    println(listOf("a", "b") == listOf("a", "b"))
    println("" + (1..4) + " " + (1..9 step 4) + " " + (9 downTo 1 step 4))
}
