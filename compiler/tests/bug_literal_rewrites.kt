import kotlinx.serialization.*
import kotlinx.serialization.json.*

// `suspend fun main()` and `X.serializer()` used to be rewritten in the source text, string literals included.
@Serializable
data class Foo(val a: Int, val b: String)

val doc = """
suspend fun main() {
}
"""

suspend fun main() {
    println("call Foo.serializer() here")
    println("decodeFromString<Foo>(x) and encodeToString<Foo>(y)")
    print(doc)
    val s = Json.encodeToString(Foo.serializer(), Foo(1, "x"))
    println(s)
    println(Json.decodeFromString(Foo.serializer(), s))
    println(Json.encodeToString<Foo>(Foo(2, "Foo.serializer()")))
    println(Json.decodeFromString<Foo>("{\"a\":3,\"b\":\"y\"}"))
    yield()
    println("done")
}
