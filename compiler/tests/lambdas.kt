fun apply2(f: (Int) -> Int, x: Int): Int = f(f(x))

fun repeatIt(n: Int, body: () -> Unit) {
    var i = 0
    while (i < n) { body(); i++ }
}

fun <T, R> mapAll(xs: ArrayList<T>, f: (T) -> R): ArrayList<R> {
    val out = ArrayList<R>()
    for (x in xs) out.add(f(x))
    return out
}

fun <T> eval(fn: () -> T): T = fn()

fun makeAdder(k: Int): (Int) -> Int = { x -> x + k }

fun compose(f: (Int) -> Int, g: (Int) -> Int): (Int) -> Int = { x -> g(f(x)) }

class Counter(val start: Int) {
    var n = start
    fun incBy(k: Int): () -> Int = { n += k; n }
    val doubled: (Int) -> Int = { it * 2 }
}

class Button(val onClick: () -> String)

fun main() {
    println(apply2({ it * 3 }, 2))
    var count = 0
    repeatIt(4) { count++ }
    println(count)
    val add5 = makeAdder(5)
    println(add5(10))
    val f = compose(add5, { x: Int -> x * 10 })
    println(f(1))
    val strs = ArrayList<Int>()
    strs.add(1); strs.add(22)
    val lens = mapAll(strs) { "v" + it }
    println(lens[1])
    println(eval { 40 + 2 })
    println(eval { "s" })
    val c = Counter(10)
    val inc = c.incBy(3)
    inc(); inc()
    println(c.n)
    println(c.doubled(21))
    val b = Button { "clicked" }
    println(b.onClick())
    val fs = ArrayList<() -> Int>()
    for (i in 0 until 3) fs.add { i * i }
    var s = 0
    for (g in fs) s += g()
    println(s)
    val greet: (String, Int) -> String = { name, times -> name + times }
    println(greet("x", 3))
    var total = 0L
    val acc = { v: Long -> total += v }
    acc(5L); acc(7L)
    println(total)
    val nested = { a: Int -> { b: Int -> a * b } }
    println(nested(6)(7))
}
