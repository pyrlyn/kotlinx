// Leak test: ArrayList of objects, HashMap, StringBuilder, interfaces.
interface Shape { fun area(): Long }
class Sq(val s: Long) : Shape { override fun area(): Long = s * s }
class Re(val w: Long, val h: Long) : Shape { override fun area(): Long = w * h }
class P(val x: Long)

fun main() {
    var total = 0L
    for (r in 0 until 10) {
        val shapes = ArrayList<Shape>()
        for (i in 0 until 100) { if (i % 2 == 0) shapes.add(Sq(i.toLong())) else shapes.add(Re(i.toLong(), 2L)) }
        for (s in shapes) total += s.area()
        val ps = ArrayList<P>()
        for (i in 0 until 50) ps.add(P(i.toLong()))
        total += ps.map { it.x * 2 }.sumOf { it }
        val m = HashMap<Long, Long>()
        for (i in 0 until 100) m[i.toLong()] = i.toLong() * 3
        total += m.size.toLong()
        val sb = StringBuilder()
        for (i in 0 until 10) sb.append(i)
        total += sb.length.toLong()
    }
    println(total)
}
