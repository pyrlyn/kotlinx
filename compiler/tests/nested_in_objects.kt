object Registry {
    class Entry(val key: String) { fun show() = "entry $key" }
    fun make(k: String) = Entry(k)
}

fun main() {
    println(Registry.make("a").show())
    println(Registry.Entry("b").show())
}
