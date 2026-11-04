// kotlinx.serialization subset (Kotlinx.kt): @Serializable classes, Json encode/decode, JsonElement tree.
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class Address(val city: String, val zip: Int)

@Serializable
data class User(val name: String, val age: Int, val score: Double, val admin: Boolean, val id: Long,
                val tags: List<String>, val home: Address, val nick: String?, val past: List<Address>)

fun main() {
    val u = User("Ann \"A\"", 31, 2.5, true, 9000000000L, listOf("x", "y"), Address("Kyiv", 1001), null,
        listOf(Address("Lviv", 79000)))
    val s = Json.encodeToString(User.serializer(), u)
    println(s)
    val back = Json.decodeFromString(User.serializer(), s)
    println(back)
    println(back == u)
    val v = Json.decodeFromString(User.serializer(), """
        {"name": "Bo", "age": -4, "score": 1e2, "admin": false, "id": 1, "tags": [],
         "home": {"city": "Odesa\u0021", "zip": 65000}, "nick": "b", "past": []}
    """)
    println(v.name + " " + v.age + " " + v.score + " " + v.home.city + " " + v.nick)
    val e = Json.parseToJsonElement("[1, {\"k\": [true, null]}, \"s\\n\"]")
    println(e)
    if (e is JsonArray) println(e.size)
    try { Json.decodeFromString(Address.serializer(), "{\"city\": \"X\"}") } catch (ex: SerializationException) { println(ex.message) }
    try { Json.parseToJsonElement("{\"a\" 1}") } catch (ex: SerializationException) { println(ex.message) }
}
