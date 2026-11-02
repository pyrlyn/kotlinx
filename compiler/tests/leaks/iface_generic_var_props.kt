// Properties of generic interfaces (getter type substituted per implementation) and interface `var` properties
// (abstract setter `$set$x`, implemented by stored fields, assigned through the interface).
interface Holder<T> { val value: T }
class StrHolder : Holder<String> { override val value = "str" }
data class Id(override val value: Int, val tag: String) : Holder<Int>

interface Sized<T> { var size: T }
open class Base { open var size: Int = 56 }
open class Derived : Sized<Int>, Base()

interface Named { var name: String }
class Person(override var name: String) : Named

class Wrapper(h: Holder<Int>) : Holder<Int> by h

fun main() {
    val h: Holder<String> = StrHolder()
    println(h.value)
    val id: Holder<Int> = Id(3, "x")
    println(id.value + 1)
    val s: Sized<Int> = Derived()
    s.size = 7
    s.size += 1
    println(s.size)
    val n: Named = Person("a")
    n.name = n.name + "b"
    n.name += "c"
    println(n.name)
    println(Wrapper(Id(41, "y")).value + 1)
}
