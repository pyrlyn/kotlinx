class Target(val n: Int)

class Buildee<CT> {
    fun materialize(): CT = Target(7) as CT
    val value: CT = Target(8) as CT
}

fun <FT> build(block: Buildee<FT>.() -> Unit): Buildee<FT> { val b = Buildee<FT>(); b.block(); return b }
fun consume(t: Target) { println("consumed ${t.n}") }

fun main() {
    build { consume(materialize()) }
    build { consume(value) }
}
