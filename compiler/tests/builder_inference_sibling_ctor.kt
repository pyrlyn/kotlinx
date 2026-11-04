// A bare generic constructor (`Buildee()`) in a `when` branch next to builder calls: its type arguments come from
// the sibling builder's inferred type (Kotlin infers the branches' common type).
class TargetType(val n: Int)

class Buildee<TV> {
    var count = 0
    fun setTypeVariable(value: TV) { count++ }
}

fun <PTV> build(instructions: Buildee<PTV>.() -> Unit): Buildee<PTV> = Buildee<PTV>().apply(instructions)

fun pick(s: String) {
    val b = when (s) {
        "true" -> build { setTypeVariable(TargetType(1)) }
        "false" -> build {}
        else -> Buildee()
    }
    println(s + " " + b.count)
}

fun main() {
    pick("true")
    pick("false")
    pick("other")
}
