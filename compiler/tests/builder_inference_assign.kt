// Builder inference from assignments inside the builder lambda.
class Buildee<CT> {
    var stored: CT? = null
    fun yield(arg: CT) { stored = arg }
    fun materialize(): CT = stored!!
}
fun <FT> build(instructions: Buildee<FT>.() -> Unit): Buildee<FT> = Buildee<FT>().apply(instructions)
class UserKlass(val n: Int)
fun main() {
    val arg = UserKlass(7)
    // materialize-case: `temp = materialize()` with `var temp = arg`
    val b1 = build {
        yield(arg)
        var temp = arg
        temp = materialize()
        println("m " + temp.n)
    }
    // yield-case: `var temp = materialize(); temp = arg`
    val b2 = build {
        if (arg.n > 100) {
            var temp = materialize()
            temp = arg
            println("y " + temp.n)
        }
    }
    println(b1.materialize().n)
    println(b2.stored == null)
}
