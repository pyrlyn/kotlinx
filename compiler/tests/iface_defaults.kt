// Default interface methods: inherited, overridden in subclasses, calling other members and properties.
interface Greeter {
    val name: String
    fun greet(): String = "Hello, " + name + suffix()
    fun suffix(): String = "!"
    fun shout() { println(greet() + "!!") }
}
open class En(override val name: String) : Greeter
class Polite(name: String) : En(name) { override fun suffix(): String = ", please." }
class Ua : Greeter { override val name: String get() = "Ivan"; override fun greet(): String = "Pryvit, " + name }
fun main() {
    val gs = arrayListOf<Greeter>(En("Bob"), Polite("Ann"), Ua())
    for (g in gs) { println(g.greet()); g.shout() }
}
