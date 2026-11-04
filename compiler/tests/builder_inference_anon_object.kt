// Builder inference through an anonymous object inside the builder lambda: its body calls the lambda's
// implicit receiver. Anonymous objects in plain lambdas (no receiver) are unaffected.
class Buildee<CT> {
    val items = ArrayList<CT>()
    fun yield(arg: CT) { items.add(arg) }
    fun materialize(): CT = items[0]
}

fun <FT> build(instructions: Buildee<FT>.() -> Unit): Buildee<FT> = Buildee<FT>().apply(instructions)

interface Greeter { fun greet(): String }

fun consume(arg: String) { println("consume " + arg) }

fun yieldInInit() {
    val arg: String = "a"
    val b = build {
        object {
            init {
                yield(arg)
            }
        }
    }
    println(b.items)
}

fun materializeInMethod() {
    val b = build {
        yield(7)
        val o = object : Greeter {
            override fun greet(): String = "got " + materialize()
        }
        println(o.greet())
    }
    println(b.items.size)
}

fun plainLambda() {
    val f = { n: Int ->
        val g = object : Greeter { override fun greet(): String { consume("x"); return "plain" } }
        g.greet() + " " + n
    }
    println(f(3))
}

fun main() {
    yieldInInit()
    materializeInMethod()
    plainLambda()
}
