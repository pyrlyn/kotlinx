interface Named { fun name(): String }
open class Animal(val n: String) : Named { override fun name() = "animal $n" }
class Dog : Animal("dog") { override fun name() = "dog" }
class Cat : Animal("cat")
class Rock : Named { override fun name() = "rock" }

fun <T> pick(a: T, b: T, first: Boolean): T = if (first) a else b
fun <T : Named> both(a: T, b: T): String = a.name() + "/" + b.name()

fun main() {
    val x = pick(Dog(), Cat(), false)
    println(x.name())
    println(x.n)
    println(both(Dog(), Rock()))
    println(pick(1, 2, true))
    println(pick(Dog(), Dog(), true).name())
}
