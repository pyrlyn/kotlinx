fun main() {
    val sb = StringBuilder()
    sb.append("ok!")
    println(sb[1])
    for ((i, c) in sb.withIndex()) print("$i$c ")
    println()
}
