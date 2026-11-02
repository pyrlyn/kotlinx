val sb = StringBuilder()

interface Greeter {
    val name: String get() = "world"
    private fun prefix() = "hello "
    fun greet() = prefix() + name
    fun twice(n: Int) = n
    fun log() = record()
    fun record()
    fun isLong() = greet().length > 5
}

class En : Greeter {
    override fun record() { sb.append("rec;") }
}

class Named(override val name: String) : Greeter {
    override fun record() { sb.append(name) }
}

fun main() {
    val gs = ArrayList<Greeter>()
    gs.add(En()); gs.add(Named("kx"))
    for (g in gs) {
        println(g.greet())
        println(g.twice(21) * 2)
        g.log()
        println(g.isLong())
    }
    println(sb.toString())
}
