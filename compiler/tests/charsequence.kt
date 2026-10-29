
interface IB { fun foo(): CharSequence }
interface IC : IB
class D : IC { override fun foo(): String = "OK" }

fun len(c: CharSequence): Int = c.length
fun firstOrDot(c: CharSequence?): Char = if (c == null || c.length == 0) '.' else c[0]

fun main() {
    val b: IB = D()
    val cs: CharSequence = b.foo()
    println("$cs ${len("hello")} ${firstOrDot(null)} ${firstOrDot("xy")}")
    val a: Any = "str"
    println(a is CharSequence)
    println(cs == "OK")
}
