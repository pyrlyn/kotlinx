class A(val x: Int)

fun main() {
    val p = A::x
    println(p.get(A(42)))
    val a = A(7)
    val q = a::x
    println(q.get() + 1)
    var v = "local"
    fun read(): String
    {
        return lazy { v }::value.get()
    }
    println(read())
}
