// Closures: captured objects retained by the closure and released with it, closures stored in lists and
// fields, boxed captured vars, closures returned from functions, a closure capturing `this` held by a val
// property of the same object (a reference cycle through a val), and throw on a path never taken.
class Node(val v: Int)

class Holder(val k: Int) {
    val f: () -> Int = { k + 1 }
    var g: () -> Int = { 0 }
    fun bind() { g = { k * 2 } }
}

class Self(val k: Int) {
    val f: () -> Int = { this.k + 1 }
}

fun adder(n: Node): (Int) -> Int = { x -> x + n.v }

fun twice(f: (Int) -> Int, x: Int): Int = f(f(x))

fun check(x: Int): Int { if (x < 0) throw IllegalStateException("neg $x"); return x }

fun main() {
    var sum = 0L
    val fs = ArrayList<(Int) -> Int>()
    for (i in 0 until 1000) {
        val n = Node(i)
        val a = adder(n)
        sum += a(1)
        sum += twice({ it + n.v }, 0)
        var cnt = 0
        val inc = { cnt += 1 }
        inc(); inc()
        sum += cnt
        if (fs.size < 5) fs.add(a) else fs[i % 5] = a
        val h = Holder(i)
        h.bind()
        sum += h.f() + h.g()
        val s = "s" + i
        val sf = { s.length }
        sum += sf()
        sum += check(1)
        sum += Self(i).f()
    }
    for (f in fs) sum += f(0)
    println(sum)
}
