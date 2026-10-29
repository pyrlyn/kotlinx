// Interface properties returning fresh strings and stored references, read through the interface in a loop.
interface Named { val name: String; val tag: String }
class Item(override val name: String, val n: Int) : Named { override val tag: String get() = "t" + n }
fun main() {
    var len = 0
    for (i in 0 until 400) {
        val x: Named = Item("item" + i, i)
        len += x.name.length + x.tag.length
    }
    println(len)
}
