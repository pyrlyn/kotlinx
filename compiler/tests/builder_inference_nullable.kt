class User(val n: String)
class Buildee<CT> {
    var last: CT? = null
    fun yield(arg: CT) { last = arg }
    fun materialize(): CT = null as CT
}
fun <FT> build(instructions: Buildee<FT>.() -> Unit): Buildee<FT> = Buildee<FT>().apply(instructions)
class Holder<T> { fun none(): T = null as T }

fun main() {
    val arg: User? = null
    val a = build { yield(arg) }
    println(a.last == null)
    fun consume(u: User?) = println("consume " + (u?.n ?: "null"))
    build { consume(materialize()) }
    val u: User? = User("x")
    val b = build { yield(u) }
    println(b.last?.n)
    println(Holder<User>().none() == null)
}
