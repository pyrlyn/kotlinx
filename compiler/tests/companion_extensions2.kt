// Companion extensions, boundary cases of the token rewrite: continuation lines, multi-line headers and bodies,
// named companions, classes declared after their extensions, modifiers.
interface Named { fun name(): String }

companion fun Late.make(): Late = Late(
    1,
    2
)

companion fun Late.sum(a: Int,
                       b: Int): Int =
    a +
        b

companion val Late.label: String =
    "late"

class Late(val a: Int, val b: Int) : Named
{
    override fun name(): String = "Late(" + a + "," + b + ")"
}

class Gen<T>(val v: T) {
    fun get(): T = v
}

companion fun Gen.zero(): Int = listOf(1, 2, 3).map {
    it * 0
}.size

class Fact {
    val x = 1
    companion object Factory {
        val seed = 7
    }
}

companion private fun Fact.seeded(): Int = seed * 6
companion internal val Fact.tag = "F"

class Empty {
    fun hi() = "hi"
}

companion fun Empty.build(): Empty { val e = Empty(); return e }

fun main() {
    println(Late.make().name()); println(Late.sum(2, 3)); println(Late.label)
    println(Gen.zero()); println(Gen("g").get())
    println(Fact.seeded()); println(Fact.tag); println(Fact.Factory.seed); println(Fact().x)
    println(Empty.build().hi())
}
