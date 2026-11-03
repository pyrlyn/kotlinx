class V(val x: Int)

class Ctx(val scale: Int) {
    operator fun V.plus(o: V) = V((x + o.x) * scale)
    operator fun V.get(i: Int) = x + i
    fun run(a: V, b: V): Int { val c = a + b; return c[1] }
}

fun main() {
    println(Ctx(2).run(V(1), V(3)))
}
