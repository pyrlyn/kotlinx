// Builder inference: a context-parameter function or property used in the lambda fixes the receiver's type.
class Controller<E>(val e: E)

fun <E, A> either(a: Any?, block: Controller<E>.() -> A): A = Controller(a as E).block()

context(c: Controller<String>)
fun findUser(): String = c.e

context(c: Controller<String>)
val prop: String get() = c.e

context(c: Controller<Int>)
fun twice(): Int = c.e * 2

fun main() {
    val x1 = either("O") { findUser() }
    val x2 = either("K") { prop }
    println(x1 + x2)
    println(either(21) { twice() })
    println(either("len") { findUser().length + prop.length })
}
