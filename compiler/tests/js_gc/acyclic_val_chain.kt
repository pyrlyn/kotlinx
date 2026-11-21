class P(val x: Int, val next: P?)
fun main() { var p: P? = null; for (i in 0 until 10) p = P(i, p); val xs = ArrayList<P>(); xs.add(p!!); var s = 0; var q: P? = p; while (q != null) { s += q.x; q = q.next }; println(s + xs.size) }
