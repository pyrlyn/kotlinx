class User(val n: String)
class Buildee<CT> {
    var slot: CT? = null
    var variable: CT
        get() = slot!!
        set(v) { slot = v }
}
fun <FT> build(instructions: Buildee<FT>.() -> Unit): Buildee<FT> = Buildee<FT>().apply(instructions)

val <E> Buildee<E>.value: E get() = slot!!
var <E> Buildee<E>.extVar: E
    get() = slot!!
    set(v) { slot = v }
fun Buildee<User>.userOnly() { println("userOnly") }
val Buildee<User>.userProp: Int get() = 7

fun main() {
    fun consume(u: User) = println("consume " + u.n)
    val a = build { variable = User("a") }
    println(a.variable.n)
    val b = build { extVar = User("b"); consume(value) }
    println(b.value.n)
    val c = build { this.userOnly(); slot = User("c") }
    println(c.variable.n)
    val d = build { println(this.userProp); slot = User("d") }
    println(d.value.n)
}
