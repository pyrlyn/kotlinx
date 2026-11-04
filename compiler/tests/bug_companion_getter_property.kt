// Regression: companion properties with a custom getter are visible as `C.x`.
class D {
    companion object {
        val base = 40
        val k: Int get() = base + 2
        val s: String
            get() = "s" + k
    }
    fun twice(): Int = k * 2
}
fun main() {
    println(D.k)
    println(D.s)
    println(D().twice())
}
