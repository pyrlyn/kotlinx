// String.toInt/toLong/toIntOrNull/toLongOrNull, substring and List.sum().
fun main() {
    println("123".toInt() + 1)
    println("-2147483648".toInt())
    println("+7".toInt())
    println("9223372036854775807".toLong())
    println("12a".toIntOrNull())
    println("2147483648".toIntOrNull())
    println("".toLongOrNull())
    println("-5".toLongOrNull())
    val d = 3.9
    println(d.toInt())
    try { "x1".toInt() } catch (e: NumberFormatException) { println(e.message) }
    println("hello".substring(1, 3))
    println("hello".substring(2))
    println("hello".substring(5).length)
    try { "ab".substring(1, 5) } catch (e: IndexOutOfBoundsException) { println(e.message) }
    println(listOf(1, 2, 3).sum())
    println(listOf(1L, 5000000000L).sum())
    println(listOf(1.5, 2.25).sum())
    println(ArrayList<Int>().sum())
}
