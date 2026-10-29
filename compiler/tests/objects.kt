interface Greeter { fun greet(n: String): String }

object Counter {
    var count = 0
    val step = 2
    fun inc(): Int { count += step; return count }
}

object Hello : Greeter {
    override fun greet(n: String): String = "hello $n"
}

class Temp(val c: Double) {
    fun f(): Double = c * SCALE + OFFSET
    companion object {
        val SCALE = 1.8
        const val OFFSET = 32.0
        var made = 0
        fun of(c: Double): Temp { made++; return Temp(c) }
    }
}

class Id(val v: Int) {
    companion object Factory {
        fun make(): Id = Id(next())
        var n = 100
        fun next(): Int = n++
    }
}

fun use(g: Greeter): String = g.greet("x")

fun main() {
    Counter.inc()
    println(Counter.inc())
    println(Counter.count)
    println(Hello.greet("bob"))
    println(use(Hello))
    val t = Temp.of(100.0)
    println(t.f())
    println(Temp.made)
    println(Temp.Companion.SCALE)
    println(Id.make().v + Id.make().v)
    println(Id.Factory.n)
}
