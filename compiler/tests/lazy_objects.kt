// Objects and companions are initialized lazily, on first access.
var log = ""
object Config {
    val name: String = "cfg"
    init { log += "Config;" }
    fun greet(): String = "hi " + name
}
object Counter { var n = 0; init { log += "Counter;" } }
class Host { companion object { val tag = "host"; init { log += "Companion;" } } }
fun main() {
    log += "main;"
    println(log)
    Counter.n++
    Counter.n += 2
    println(Counter.n)
    println(log)
    println(Config.greet())
    println(Config.name)
    println(log)
    println(Host.tag)
    println(log)
    more()
}

class Widget { init { log += "Widget;" }; companion object { const val KIND = "w"; init { log += "WidgetCompanion;" } } }
object Limits { const val MAX = 10; init { log += "Limits;" } }

fun more() {
    log = ""
    println(Limits.MAX + 1)
    println(Widget.KIND)
    println(log)
    Widget(); Widget()
    println(log)
}
