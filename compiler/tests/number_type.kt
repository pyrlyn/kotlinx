fun f(n: Number): Long = n.toLong() + n.toInt()
fun main() { val x: Number = 3.7; println(f(5)); println(x.toInt()); println(f(2L)); val y: Number? = null; println(y ?: 1) }
