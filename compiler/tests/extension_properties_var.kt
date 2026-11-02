// Mutable top-level extension properties and member extension properties.
class Box(var v: Int)

var Box.doubled: Int
    get() = v * 2
    set(x) { v = x / 2 }

var ArrayList<Int>.head: Int
    get() = this[0]
    set(x) { this[0] = x }

val Int.isEven: Boolean get() = this % 2 == 0

class Scale(val k: Int) {
    val Box.scaled: Int get() = v * k
    var Box.scaledVar: Int
        get() = v * k
        set(x) { v = x / k }

    fun run(b: Box): String {
        b.scaledVar = 30
        b.scaledVar += k
        return "${b.scaled} ${b.v}"
    }
}

fun main() {
    val b = Box(3)
    println(b.doubled)
    b.doubled = 20
    println(b.v)
    b.doubled += 4
    println(b.v)
    val l = arrayListOf(1, 2)
    l.head = 7
    println("${l.head} ${l.size}")
    println("${4.isEven} ${5.isEven}")
    println(Scale(3).run(Box(1)))
}
