// Bitwise members in call form, and members winning over an extension on the nullable type for `x!!.f()`.
fun Long?.inv(): Long = this!!.inv()
fun Int?.shl(bits: Int?): Int = this!!.shl(bits!!)

fun main() {
    val a = 10; val b = 10L; val u = 5u
    println(a.inv()); println(b.inv()); println(a.shl(2)); println(b.and(3L)); println(u.inv())
    val n: Long? = 10
    println(n.inv())
    val m: Int? = 3
    println(m.shl(4))
}
