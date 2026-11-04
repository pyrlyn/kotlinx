class Target(val n: String)
class Buildee<TV> {
    var v: TV? = null
    fun set(x: TV) { v = x }
}
fun <PTV> build(instructions: Buildee<PTV>.() -> Unit): Buildee<PTV> = Buildee<PTV>().apply(instructions)
fun <T> pick(vararg bs: Buildee<T>): Buildee<T> = bs[bs.size - 1]

fun main() {
    val flag = true
    val a = if (flag) build { set(Target("a")) } else build {}
    println(a.v?.n)
    val b = when (flag) { false -> build { set(Target("b")) }; else -> build {} }
    println(b.v == null)
    val c = pick(build { set(Target("c")) }, build {})
    println(c.v == null)
}
