interface Named { fun name(): String }
interface Greeter : Named {
    fun greet(): String = "Hello, " + name()
}
interface Loud : Greeter { fun shout(): String = greet() + "!" }
class Person(val n: String) : Loud { override fun name(): String = n }
class Robot : Greeter {
    override fun name(): String = "R2"
    override fun greet(): String = "Beep " + name()
}
fun describe(x: Named): String = x.name()
fun main() {
    val p = Person("Ann")
    println(p.shout())
    val g: Greeter = p
    println(describe(g))
    val r: Named = Robot()
    println(r is Greeter)
    println((r as Greeter).greet())
    val list = listOf<Greeter>(p, Robot())
    for (x in list) println(x.greet() + " / " + describe(x))
}
