class Ctx(val tag: String) {
    fun <T> T.tagged(): String = tag + this.toString()
    fun run(): String = 5.tagged() + "x".tagged()
}
fun main() { println(Ctx("#").run()) }
