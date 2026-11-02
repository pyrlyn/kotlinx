// Class literals: `T::class`, `e::class`, simpleName / qualifiedName, equality.
class Klass(val s: String) {
    override fun equals(other: Any?): Boolean = other != null && other is Klass && this::class == Klass::class && s == other.s
}

inline fun <reified T> name(): String? = T::class.simpleName

fun <T> nameOf(x: T): String? = x::class.simpleName

fun main() {
    println(Int::class.qualifiedName + " " + String::class.qualifiedName + " " + Any::class.qualifiedName)
    println(Klass::class.simpleName + " " + Klass::class.qualifiedName)
    var x = 1
    val k = (x++)::class
    println("${k == Int::class} ${k == String::class} $x")
    println(Klass("a") == Klass("a"))
    println("${name<Long>()} ${nameOf("s")} ${nameOf(2.0)}")
    println(ArithmeticException::class)
}
