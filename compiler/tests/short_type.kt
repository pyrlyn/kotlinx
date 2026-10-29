// Short (stored as Int, toShort() truncates) and MIN/MAX constants.
fun half(x: Short): Short = (x / 2).toShort()
fun main() {
    val a: Short = 40000.toShort()
    println(a)
    println(half(a))
    println(Short.MAX_VALUE.toInt() + 1)
    println((Short.MIN_VALUE - 1).toShort())
    println(Byte.MAX_VALUE)
    println(Long.MIN_VALUE)
    val xs = arrayListOf<Short>(1.toShort(), 2.toShort())
    println(xs)
}
