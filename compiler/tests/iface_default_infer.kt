// Interface defaults: inferred return types, most specific default wins, private interface methods.
interface A { fun foo() = "A"; fun n() = 40 + 2; fun both() = foo() + n() }
interface B : A
interface C : A { override fun foo() = "C" }
interface D : B, C
class Impl : D

interface P {
    private fun secret() = "ok"
    fun reveal() = secret() + "!"
}
class Q : P { private fun secret() = "class" ; fun mine() = secret() }

fun main() {
    println(Impl().foo())
    println(Impl().both())
    println(Q().reveal() + " " + Q().mine())
}
