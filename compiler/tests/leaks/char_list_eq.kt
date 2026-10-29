// Char lists built and compared in a loop: all released.
fun main() {
    var n = 0
    for (r in 0 until 300) {
        val a = mutableListOf<Char>(); val b = mutableListOf<Char>()
        for (c in 'a'..'z') { a += c; b += c }
        if (a == b) n++
    }
    println(n)
}
