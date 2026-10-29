// Nested classes, enums, objects and interfaces; inner classes using outer members, this@Outer and outer.Inner().
class Outer(val base: Int) {
    var count = 0
    class Nested(val x: Int) { fun twice(): Int = x * 2 }
    enum class Mode { A, B }
    object Registry { val id = 42 }
    interface Shape { fun area(): Int }
    inner class Counter(val step: Int) : Shape {
        fun bump(): Int { count += step; return count + base }
        fun self(): Outer = this@Outer
        override fun area(): Int = helper() * step
    }
    fun helper(): Int = base + 1
    fun make(s: Int): Counter = Counter(s)
}

fun main() {
    val n = Outer.Nested(21)
    println(n.twice())
    val m: Outer.Mode = Outer.Mode.B
    println(m)
    println(Outer.Registry.id)
    val o = Outer(100)
    val c = o.Counter(3)
    println(c.bump())
    println(c.bump())
    println(o.count)
    println(o.make(5).bump())
    println(c.self().base)
    val s: Outer.Shape = c
    println(s.area())
}
