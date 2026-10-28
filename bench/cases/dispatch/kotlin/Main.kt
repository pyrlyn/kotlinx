// dispatch: interface calls over 1000 mixed shapes, 20k rounds.
@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)
@file:Suppress("DEPRECATION_ERROR")

interface Shape {
    fun area(): Long
    fun scale(k: Long): Long
}

class Square(val s: Long) : Shape {
    override fun area(): Long = s * s
    override fun scale(k: Long): Long = s * k
}

class Rect(val w: Long, val h: Long) : Shape {
    override fun area(): Long = w * h
    override fun scale(k: Long): Long = (w + h) * k
}

class Tri(val b: Long, val h: Long) : Shape {
    override fun area(): Long = b * h / 2
    override fun scale(k: Long): Long = b * k - h
}

fun main() {
    val shapes = ArrayList<Shape>()
    for (i in 0 until 1000) {
        val v = i.toLong()
        when (i % 3) {
            0 -> shapes.add(Square(v))
            1 -> shapes.add(Rect(v, v + 1))
            else -> shapes.add(Tri(v, v + 2))
        }
    }
    val t0 = kotlin.system.getTimeNanos()
    var sum = 0L
    for (r in 0 until 20_000) {
        val k = (r and 7).toLong()
        for (s in shapes) sum += s.area() + s.scale(k)
    }
    val t1 = kotlin.system.getTimeNanos()
    println("checksum=$sum nanos=${t1 - t0}")
}
