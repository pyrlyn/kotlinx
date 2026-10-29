class Holder {
    lateinit var s: String
    fun get() = s
}

fun main() {
    val h = Holder()
    try {
        println(h.get())
    } catch (e: UninitializedPropertyAccessException) {
        println("caught: " + e.message)
    }
    h.s = "set"
    println(h.get())

    var str = "original"
    val show = { str + "!" }
    println(show())
    str = "changed"
    println(show())
}
