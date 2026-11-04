// StringBuilder.setLength (truncate / pad) and clear().
fun main() {
    val sb = StringBuilder()
    sb.append("hello world")
    sb.setLength(5)
    println(sb.toString())
    println(sb.length)
    sb.setLength(7)
    println(sb.length)
    sb.setLength(0)
    sb.append(42)
    println(sb.toString())
    sb.clear().append("x").append(1)
    println(sb.toString())
    val parts = ArrayList<String>()
    val w = StringBuilder()
    for (c in "a,bb,ccc") {
        if (c != ',') { w.append(c); continue }
        parts.add(w.toString())
        w.setLength(0)
    }
    parts.add(w.toString())
    println(parts)
}
