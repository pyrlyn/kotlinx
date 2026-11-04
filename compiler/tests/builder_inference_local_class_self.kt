// A local class declared in a builder lambda that is itself the builder's type argument: it yields itself
// (`yield(this)`, `yield(Local())`) or consumes `materialize()` through a function taking it. (The builder keeps the
// last value in a `var`: the captured receiver makes a cycle, which the collector handles.)
class Buildee<CT> {
    var count = 0
    var last: CT? = null
    fun yield(arg: CT) { count++; last = arg }
    fun materialize(): CT = last!!
}

fun <FT> build(instructions: Buildee<FT>.() -> Unit): Buildee<FT> = Buildee<FT>().apply(instructions)

fun yieldThis() {
    val b = build {
        class Local(val n: Int) {
            fun hello() = "local " + n
            fun initialize() { yield(this) }
        }
        Local(1).initialize()
        Local(2).initialize()
    }
    println(b.count.toString() + " " + b.last!!.hello())
}

fun yieldNew() {
    val b = build {
        class Local {
            fun hello() = "new"
            fun initialize() {
                val arg: Local = Local()
                yield(arg)
            }
        }
        Local().initialize()
    }
    println(b.last!!.hello())
}

fun consumeMaterialized() {
    val b = build {
        class Local(val tag: String) {
            fun initialize() {
                fun consume(arg: Local) { println("consume " + arg.tag) }
                yield(this)
                consume(materialize())
            }
        }
        Local("first").initialize()
    }
    println(b.count)
}

fun main() {
    yieldThis()
    yieldNew()
    consumeMaterialized()
}
