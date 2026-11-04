class Target(val n: String) { fun hello(g: Greeting) = println("hello " + n + g.s) }
class Greeting(val s: String)
fun show(t: Target) = println("show " + t.n)
class Buildee<TV> {
    var consumer: (TV) -> Unit = {}
    var producer: (() -> TV)? = null
    fun setProducer(p: () -> TV) { producer = p }
    fun setConsumer(c: (TV) -> Unit) { consumer = c }
}
fun <PTV> build(instructions: Buildee<PTV>.() -> Unit): Buildee<PTV> = Buildee<PTV>().apply(instructions)

fun main() {
    val a = build { consumer = { show(it) } }
    a.consumer(Target("a"))
    val b = build {
        setProducer { Target("b") }
        setConsumer { it.hello(Greeting("!")) }
    }
    b.consumer(b.producer!!())
    val c = build { setProducer {} }
    println(c.producer!!())
}
