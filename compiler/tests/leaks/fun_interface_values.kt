// SAM conversion of function values (wrappers own the converted closure; null stays null) and fun interfaces
// extending function types: nothing may leak or be freed twice.

fun interface Action {
    fun run(): Int
}

fun interface Counter : () -> Int

fun interface Transform<T> {
    fun apply(x: T): T
}

class Box<T>(val v: T) {
    fun map(t: Transform<T>): Box<T> = Box(t.apply(v))
}

fun call(a: Action?): Int = a?.run() ?: -1

fun keep(a: Action, into: ArrayList<Action>) { into.add(a) }

fun make(k: Int): () -> Int = { k * 2 }

fun maybe(k: Int): (() -> Int)? = if (k % 2 == 0) null else make(k)

fun seven(): Int = 7

fun main() {
    var sum = 0
    val kept = ArrayList<Action>()
    for (i in 0 until 1000) {
        val s = "x$i"
        val f: () -> Int = { s.length }
        sum += call(f)
        sum += call(make(i))
        sum += call(maybe(i))
        sum += call(::seven)
        if (i % 100 == 0) keep(make(i), kept)
        val c = object : Counter { override fun invoke(): Int = s.length }
        sum += c() + call(Action(c))
        sum += Box(s).map { it + "!" }.v.length
    }
    for (a in kept) sum += a.run()
    println(sum)
    println(kept.size)
}
