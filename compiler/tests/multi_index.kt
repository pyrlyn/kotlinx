class Grid(val w: Int) {
    val cells = IntArray(6)
    operator fun get(x: Int, y: Int): Int = cells[y * w + x]
    operator fun set(x: Int, y: Int, v: Int) { cells[y * w + x] = v }
}

operator fun Array<String>.get(i: Int, j: Int) = this[i + j]
operator fun Array<String>.set(i: Int, j: Int, e: String) { this[i + j] = e }

var calls = 0
fun grid(g: Grid): Grid { calls++; return g }

fun main() {
    val g = Grid(3)
    g[1, 1] = 5
    g[1, 1] += 2
    grid(g)[2, 1]++
    grid(g)[2, 1] *= 10
    println("${g[1, 1]} ${g[2, 1]} ${g[
        0, 0]} $calls")
    val s = Array(1) { "" }
    s[1, -1] = "O"
    s[2, -2] += "K"
    println(s[-3, 3])
}
