// is/!is/as/as?, smart casts (if, &&, when subject, early return), when with is/in conditions, ClassCastException.
interface Shape { fun area(): Int }
open class Animal(val name: String) { open fun sound(): String = "..." }
class Dog(name: String) : Animal(name) { override fun sound(): String = "woof"; fun fetch(): String = name + " fetches" }
class Cat(name: String) : Animal(name), Shape { override fun area(): Int = 3 }
class Sq(val s: Int) : Shape { override fun area(): Int = s * s }

fun describe(a: Animal): String = when (a) {
    is Dog -> a.fetch()
    is Cat -> "cat " + a.area()
    else -> "animal"
}

fun grade(n: Int): String = when (n) {
    in 0..59 -> "F"
    !in 60..100 -> "?"
    else -> "P"
}

fun only(a: Animal?): String {
    if (a !is Dog) return "not a dog"
    return a.fetch()
}

fun main() {
    val xs = listOf<Animal>(Dog("rex"), Cat("tom"), Animal("gen"))
    for (x in xs) println(describe(x))
    for (x in xs) println("${x is Dog} ${x !is Cat} ${x is Shape}")
    val a: Animal = Dog("max")
    if (a is Dog && a.fetch().length > 3) println(a.fetch())
    val d = a as Dog
    println(d.sound())
    val c = xs[1] as? Dog
    println(c == null)
    val s = xs[1] as? Shape
    if (s != null) println(s.area())
    println(grade(42) + grade(77) + grade(130))
    println(only(null) + "/" + only(Cat("z")) + "/" + only(Dog("odie")))
    val n: Animal? = null
    println(n is Animal?)
    println(n is Animal)
    val sh: Shape = Sq(4)
    if (sh is Sq) println(sh.s)
    println(when (sh) { is Cat -> 1; is Sq -> sh.area(); else -> 0 })
    try {
        val bad = xs[2] as Dog
        println(bad.fetch())
    } catch (e: ClassCastException) {
        println("CCE")
    }
}
