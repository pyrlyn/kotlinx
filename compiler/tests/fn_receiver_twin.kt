class A(val s: String)

fun A.greet(x: String) = s + x
fun applyR(f: A.(String) -> String, a: A) = a.f("!")
fun applyP(f: (A, String) -> String, a: A) = f(a, "?")

fun main() {
    val a = A("hi")
    println(applyR(A::greet, a))
    val plain: (A, String) -> String = { x, y -> x.s + y }
    println(applyR(plain, a))
    val recv: A.(String) -> String = { y -> s + y + y }
    println(applyP(recv, a))
}
