class P(val x: Int)
var log = ""
var counter = 0
val base = 40
const val K = 2
var obj = P(1)
fun bump(s: String) { log += s; counter++ }
fun main() {
    bump("a"); bump("b")
    obj = P(obj.x + base + K)
    println(log)
    println(counter)
    println(obj.x)
}
