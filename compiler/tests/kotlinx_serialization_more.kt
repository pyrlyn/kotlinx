// @Serializable: enums by name, @SerialName, default values for missing keys, nested lists, reified decodeFromString<T>.
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.*

@Serializable
enum class Role { ADMIN, USER }

@Serializable
data class Item(val id: Int, @SerialName("item_name") val name: String, val qty: Int = 1, val tags: List<String> = listOf())

@Serializable
data class Order(val no: Long, val items: List<Item>, val role: Role, val note: String? = null, val nested: List<List<Int>>)

fun main() {
    val o = Order(7L, listOf(Item(1, "pen"), Item(2, "ink", 3, listOf("blue"))), Role.ADMIN, null, listOf(listOf(1, 2), listOf<Int>()))
    val s = Json.encodeToString(Order.serializer(), o)
    println(s)
    val back = Json.decodeFromString<Order>(s)
    println(back == o)
    val d = Json.decodeFromString(Order.serializer(), """{"no": 1, "items": [{"id": 5, "item_name": "cup"}], "role": "USER", "nested": [[3]]}""")
    println(d)
    try { Json.decodeFromString(Order.serializer(), """{"no": 1, "items": [], "role": "BOSS", "nested": []}""") } catch (e: SerializationException) { println(e.message) }
}
