// Referential equality `===` / `!==`.
data class P(val x: Int)
interface I
class C : I


fun main() {
    val a = P(1); val b = P(1); val c = a
    println("${a == b} ${a === b} ${a === c} ${a !== b}")
    val i: I = C(); val j: I = i
    println("${i === j} ${i !== C()}")
    val n: P? = null
    println("${n === null} ${a !== null}")
    val x = 5
    println(x === 5)
    val arr = intArrayOf(1); val arr2 = arr
    println("${arr === arr2} ${arr === intArrayOf(1)}")
}
