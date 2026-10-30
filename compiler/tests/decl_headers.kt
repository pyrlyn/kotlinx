// Declaration headers: private primary constructors, bodies starting on the next line.
open class Money private constructor(val cents: Int)
{
    companion object { fun of(units: Int) = Money(units * 100) }
}

object Config
{
    val name = "cfg"
}

class Pair2 public constructor(val a: Int, val b: Int)

fun main() {
    println(Money.of(3).cents)
    println(Config.name)
    val p = Pair2(1, 2)
    println(p.a + p.b)
}
