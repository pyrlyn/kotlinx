class Buildee<CT> {
    private val items = ArrayList<CT>()
    fun yield(arg: CT) { items.add(arg) }
    fun materialize(): CT = items[0]
    fun size() = items.size
}
fun <FT> build(instructions: Buildee<FT>.() -> Unit): Buildee<FT> = Buildee<FT>().apply(instructions)
fun execute(action: () -> Unit) = action()

class Owner(val name: String) {
    fun viaLabel(): Int = build { yield(this@Owner) }.size()
    fun viaLocalFun(): String {
        fun consume(o: Owner): String = o.name
        var r = ""
        build { yield(this@Owner); r = consume(materialize()) }
        return r
    }
    fun viaGenericLocalFun(): Int {
        fun <T> share(a: T, b: T) {}
        return build { yield(this@Owner); share(this@Owner, materialize()) }.size()
    }
}

fun main() {
    val b = build { execute { yield("nested") } }
    println(b.materialize())
    val c = build { try { yield(1) } catch (e: Exception) {} ; for (i in 2..3) yield(i) }
    println(c.size())
    println(Owner("o").viaLabel())
    println(Owner("p").viaLocalFun())
    println(Owner("q").viaGenericLocalFun())
}
