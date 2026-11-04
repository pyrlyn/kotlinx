// `Nothing` / `Nothing?` as type arguments: explicit (`Box<Nothing>()`, `Box<Nothing?>()`) and inferred by a builder
// whose only information is `yield(throw e)` or `yield(null)`.
class Buildee<CT> {
    var count = 0
    fun yield(arg: CT) { count++ }
}

fun <FT> build(instructions: Buildee<FT>.() -> Unit): Buildee<FT> = Buildee<FT>().apply(instructions)

fun explicit() {
    val b = Buildee<Nothing>()
    try { b.yield(throw IllegalStateException("boom")) } catch (e: IllegalStateException) { println("caught " + e.message) }
    val c = Buildee<Nothing?>()
    c.yield(null)
    c.yield(null)
    println("explicit " + b.count + " " + c.count)
}

fun inferredNothing() {
    val b = build {
        try {
            yield(throw IllegalStateException("inside"))
        } catch (e: IllegalStateException) { println("caught " + e.message) }
    }
    println("nothing " + b.count)
}

fun inferredNullableNothing() {
    val b = build { yield(null) }
    println("null " + b.count)
}

fun otherConstraintWins() {
    val b = build {
        yield(null)
        yield("s")
    }
    println("string " + b.count)
}

fun main() {
    explicit()
    inferredNothing()
    inferredNullableNothing()
    otherConstraintWins()
}
