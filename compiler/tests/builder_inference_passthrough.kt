// Builder inference through a generic pass-through function (`yield(id(arg))`, `consume(id(materialize()))`) and
// with the builder as the lambda's parameter rather than its receiver (`UserKlass.(Buildee<FT>) -> Unit`).
class Buildee<CT> {
    var count = 0
    var last: CT? = null
    fun yield(arg: CT) { count++; last = arg }
    fun materialize(): CT = last!!
}

fun <FT> build(instructions: Buildee<FT>.() -> Unit): Buildee<FT> = Buildee<FT>().apply(instructions)

class UserKlass(val name: String)

fun <T> id(arg: T): T = arg

fun consume(arg: UserKlass) { println("consume " + arg.name) }

fun <FT> buildWith(instructions: UserKlass.(Buildee<FT>) -> Unit): Buildee<FT> {
    val b = Buildee<FT>()
    UserKlass("recv").instructions(b)
    return b
}

fun main() {
    val arg = UserKlass("a")
    val b1 = build { yield(id(arg)) }
    println(b1.last!!.name)
    val b2 = build {
        yield(UserKlass("m"))
        consume(id(materialize()))
    }
    println(b2.count)
    val b3 = buildWith { it.yield(this) }
    println(b3.last!!.name)
    val b4 = buildWith { b -> b.yield(name.length) }
    println(b4.last!! + 1)
}
