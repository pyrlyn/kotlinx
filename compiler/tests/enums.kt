enum class Color { RED, GREEN, BLUE }

enum class Planet(val mass: Double, val radius: Int) {
    MERCURY(3.3, 2439),
    EARTH(5.97, 6371);

    fun big(): Boolean = radius > 5000
    fun describe(): String = "$name#$ordinal"
}

interface Named { fun label(): String }

enum class Op : Named {
    ADD, SUB;
    override fun label(): String = if (this == ADD) "+" else "-"
}

fun apply(op: Op, a: Int, b: Int): Int = when (op) {
    Op.ADD -> a + b
    Op.SUB -> a - b
}

val favorite = Color.GREEN

fun main() {
    val c = Color.BLUE
    println(c)
    println(c.name + " " + c.ordinal)
    println(c == Color.BLUE)
    println(c != Color.RED)
    println("fav=$favorite")
    for (x in Color.values()) print("${x.ordinal}:$x ")
    println()
    println(Color.entries.size)
    println(Planet.EARTH.big())
    println(Planet.MERCURY.describe())
    println(Planet.EARTH.mass * 2)
    println(apply(Op.SUB, 5, 3))
    val n: Named = Op.ADD
    println(n.label())
    val s = when (c) {
        Color.RED -> "r"
        Color.GREEN -> "g"
        else -> "other"
    }
    println(s)
    val xs = listOf(1, 2, 3)
    println(xs.size + xs[2])
}
