// UByte/UShort (UInt-backed, toUByte/toUShort truncate) and unsigned MIN/MAX constants.
fun main() {
    val b: UByte = 300u.toUByte()
    println(b)
    println(255.toUByte())
    println((-1).toUByte())
    println(70000u.toUShort())
    println(UByte.MAX_VALUE)
    println(UInt.MAX_VALUE)
    println(ULong.MAX_VALUE)
    val l = arrayListOf<UByte>(1u.toUByte(), 200u.toUByte())
    println(l)
}
