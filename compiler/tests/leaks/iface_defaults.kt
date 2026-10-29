// Default interface methods building strings and lists through the interface in a loop.
interface Bag { val items: ArrayList<String>; fun put(s: String) { items.add(s + "!") }; fun dump(): String = "" + items.size }
class B(override val items: ArrayList<String>) : Bag
fun main() {
    var n = 0
    for (i in 0 until 300) { val b: Bag = B(ArrayList<String>()); b.put("x" + i); b.put("y"); n += b.dump().length + b.items.size }
    println(n)
}
