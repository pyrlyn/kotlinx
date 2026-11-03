fun interface Base { fun run(): String }
fun interface Named : Base
interface Greets { fun greet() = "hello" }
fun interface Counter : Greets { fun count(): Int }

fun useBase(b: Base) = b.run()

fun main() {
    println(useBase(Named { "named" }))
    val c = Counter { 3 }
    println(c.greet() + c.count())
}
