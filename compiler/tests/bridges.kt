// Overrides that narrow an interface method's return type to a different representation (bridges).
interface Z1 { fun foo(x: Int): Any }
interface Named { fun name(): Any? }

open class A : Z1, Named {
    override fun foo(x: Int): Int = 5 + x
    override fun name(): String = "A"
}

open class B : A() {
    override fun foo(x: Int): Int = 42 + x
    override fun name(): String = "B"
}

fun viaIface(z: Z1) = z.foo(1)

fun main() {
    val a: A = B()
    println(a.foo(1) + 1000)
    println(viaIface(B()))
    println(viaIface(A()))
    val n: Named = B()
    println(n.name())
    println(A().name().length)
    more()
}

// A class-typed call through an ancestor that does not implement the interface still gets the unboxed result.
interface Iz { fun bar(): Any }
open class P { fun bar(): String = "P" }
open class Q : P(), Iz

fun more() {
    val i: Iz = Q()
    println(i.bar())
    println((i as P).bar().length)
}
