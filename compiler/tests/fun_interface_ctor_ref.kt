fun interface KRunnable { fun run(): String }
fun main() {
    val mk = ::KRunnable
    val r = mk { "ran" }
    println(r.run())
    println(listOf({ "a" }, { "b" }).map(::KRunnable).map { it.run() })
}
