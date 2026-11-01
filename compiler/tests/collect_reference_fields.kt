import kotlin.native.internal.*

data class A(val x: Int)
value class BoxA(val a: A)
value class BoxInt(val x: Int)
class Holder(val n: Int, val a: A, val b: BoxA, val i: BoxInt, val s: String, val m: A?)

fun main() {
    val none: Any? = null
    val a1 = A(1)
    val a2 = A(2)
    println(none == null)
    println(1.collectReferenceFieldValues().size)
    println("str".collectReferenceFieldValues().size)
    println(BoxInt(5).collectReferenceFieldValues().size)
    println(BoxA(a1).collectReferenceFieldValues())
    println(Holder(1, a1, BoxA(a2), BoxInt(3), "s", null).collectReferenceFieldValues())
    println(Holder(1, a1, BoxA(a2), BoxInt(3), "s", a2).collectReferenceFieldValues())
}
