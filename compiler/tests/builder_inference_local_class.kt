// Builder inference through a local class declared inside the builder lambda: the class's body calls the
// lambda's implicit receiver (`yield`, `materialize`), which fixes the builder's type argument.
class Buildee<CT> {
    val items = ArrayList<CT>()
    fun yield(arg: CT) { items.add(arg) }
    fun materialize(): CT = items[0]
}

fun <FT> build(instructions: Buildee<FT>.() -> Unit): Buildee<FT> = Buildee<FT>().apply(instructions)

class UserKlass(val name: String)

fun consume(arg: UserKlass) { println("consume " + arg.name) }

fun yieldInMethod() {
    val b = build {
        class Local {
            fun go() { yield("hi") }
        }
        Local().go()
        Local().go()
    }
    println(b.items)
}

fun yieldCapturedInInit() {
    val arg: UserKlass = UserKlass("u")
    val b = build {
        class Local {
            init {
                yield(arg)
            }
        }
        Local()
    }
    println(b.items.size.toString() + " " + b.items[0].name)
}

fun materializeInInit() {
    fun localConsume(arg: UserKlass) { consume(arg) }
    val b = build {
        yield(UserKlass("first"))
        class Local {
            init {
                localConsume(materialize())
            }
        }
        Local()
    }
    println(b.items.size)
}

fun sameNameInTwoFunctions() {
    val b = build {
        class Local {
            fun go() { yield(42) }
        }
        Local().go()
    }
    println(b.items[0] + 1)
}

fun main() {
    yieldInMethod()
    yieldCapturedInInit()
    materializeInInit()
    sameNameInTwoFunctions()
}
