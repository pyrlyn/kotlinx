open class Animal
class Dog : Animal()
fun show(x: Int): String = "Int " + x
fun show(x: String): String = "String " + x
fun show(x: Double): String = "Double " + x
fun show(a: Animal): String = "Animal"
fun show(d: Dog): String = "Dog"
fun show(x: Int, y: Int): String = "two " + (x + y)
fun go(block: () -> Unit) = "unit"
fun go(block: () -> String) = "string " + block()
fun main() {
    println(show(1)); println(show("s")); println(show(2.5)); println(show(Dog())); println(show(Animal() as Animal)); println(show(1, 2))
    println(go { println("side") }); println(go { "x" })
    samMain()
}

fun interface StrSam { fun get(): String }
fun samOrSusp(block: suspend () -> Unit) = "susp"
fun samOrSusp(block: StrSam) = "sam:" + block.get()

fun samMain() {
    println(samOrSusp { Unit })
    println(samOrSusp { "x" })
}
