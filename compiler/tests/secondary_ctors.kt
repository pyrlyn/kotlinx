open class Base(val tag: String)

class Pt(val x: Int, val y: Int) {
    var label = "none"
    constructor(v: Int) : this(v, v) { label = "diag" }
    constructor(s: String) : this(s.length) { this.label = "str " + s }
    fun show(): String = "($x,$y) $label"
}

class NoPrim {
    val items = ArrayList<Int>()
    var name: String
    constructor(n: String) {
        name = n
        items.add(n.length)
    }
}

class Derived : Base {
    constructor(t: String) : super(t + "!")
}

fun main() {
    println(Pt(1, 2).show())
    println(Pt(5).show())
    println(Pt("abc").show())
    val np = NoPrim("hello")
    println(np.name + " " + np.items)
    println(Derived("d").tag)
}
