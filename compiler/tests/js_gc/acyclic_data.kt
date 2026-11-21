// The data segment as a JS string of bytes (--target=js): string constants with quotes, backslashes, newlines,
// tabs, digits that follow a NUL terminator, and UTF-8 text; constant tables of numbers.
val names = arrayOf("plain", "with \"quotes\"", "back\\slash", "tab\tand\nnewline", "0123", "9 lives", "Привет, мир", "€ ½ ✓")
val table = intArrayOf(0, 1, -1, 255, 256, 65535, -65536, 2147483647, -2147483648, 48, 57)

fun main() {
    for (n in names) println("${n.length}:$n")
    var s = 0L
    for (v in table) s = s * 31 + v
    println(s)
    println("1" + "2" + names[4] + names[5].length)
}
