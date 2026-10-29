class Box<T>(val value: T) {
    fun get(): T = value
}

class Pair2<A, B>(val first: A, val second: B) {
    fun swap(): Pair2<B, A> = Pair2(second, first)
}

class Stack<T> {
    private val items = ArrayList<T>()
    var pushes = 0
    fun push(x: T) { items.add(x); pushes++ }
    fun pop(): T = items.removeAt(items.size - 1)
    fun size(): Int = items.size
}

class Cell<T>(var v: T) {
    fun set(x: T) { v = x }
}

class Point(val x: Int, val y: Int)

fun <T> boxOf(x: T): Box<T> = Box(x)

fun sumBox(b: Box<Int>): Int = b.value + 1

fun main() {
    val bi = Box(41)
    println(bi.get() + 1)
    val bs = Box<String>("str")
    println(bs.value)
    println(sumBox(Box(9)))
    val bd = boxOf(1.5)
    println(bd.get() * 2)
    val p = Pair2("a", 3L)
    val q = p.swap()
    println(q.first + 1)
    println(q.second)
    val st = Stack<Point>()
    st.push(Point(1, 2)); st.push(Point(3, 4))
    println(st.pop().y + st.size() + st.pushes)
    val nested = Box(Box(7))
    println(nested.get().get())
    val c = Cell(Box("x"))
    c.set(Box("y"))
    println(c.v.value)
    val m = Pair2(Box(5).get(), "five")
    println(m.second + m.first)
    val ls = ArrayList<Box<Int>>()
    for (i in 0 until 4) ls.add(Box(i))
    var s = 0
    for (b in ls) s += b.value
    println(s)
}
