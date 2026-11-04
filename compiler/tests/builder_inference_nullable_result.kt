// A builder type variable bound from a lambda whose function type returns `K?` (`parse { it.length }`: K = Int),
// and a cast between different representations (`s as Int` with `s: String`), which always fails at run time.
class TypeDefinition<K : Any> {
    var parsed: K? = null
    fun parse(parser: (serializedValue: String) -> K?) { parsed = parser("hello") }
    fun serialize(printer: (value: K) -> Any?) { println("serialize " + printer(parsed!!)) }
}

fun <T : Any> defineType(definition: TypeDefinition<T>.() -> Unit): TypeDefinition<T> = TypeDefinition<T>().apply(definition)

fun castFails(s: String): Int = s as Int

fun main() {
    val d = defineType {
        parse { it.length }
        serialize { it.toString() + "!" }
    }
    println(d.parsed!! + 1)
    try {
        println(castFails("x"))
    } catch (e: ClassCastException) {
        println("cce")
    }
}
