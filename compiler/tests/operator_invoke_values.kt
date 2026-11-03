class Adder(val base: Int) {
    operator fun invoke(x: Int): Int = base + x
}

class Greeter(val g: (String) -> String) {
    operator fun invoke(name: String) = g.invoke(name)
}

fun main() {
    val a = Adder(10)
    println(a(5))
    println(Greeter { "hi $it" }("kx"))
}
