enum class Op(val sym: String) {
    PLUS("+") { override fun apply(a: Int, b: Int): Int = a + b },
    TIMES("*") {
        override fun apply(a: Int, b: Int): Int = a * b
        override fun show(): String = "times"
    },
    MINUS("-") { override fun apply(a: Int, b: Int): Int = a - b };

    abstract fun apply(a: Int, b: Int): Int
    open fun show(): String = "op " + sym
}

enum class Level {
    LOW { override fun next(): Level = HIGH },
    HIGH { override fun next(): Level = LOW };
    abstract fun next(): Level
}

fun main() {
    for (o in Op.values()) println("${o.name} ${o.ordinal} ${o.apply(6, 3)} ${o.show()}")
    println(Op.TIMES == Op.TIMES)
    println(Level.LOW.next())
    extra()
    println(when (Op.MINUS) { Op.PLUS -> 1; Op.TIMES -> 2; Op.MINUS -> 3 })
}

enum class Bar {
    ONE { override fun toString(): String = if (this != TWO && this == ONE) "custom" else "FAIL" },
    TWO;
}

fun extra() {
    println("${Bar.ONE} ${Bar.TWO} ${Bar.ONE.toString()} ${Bar.TWO.name}")
}
