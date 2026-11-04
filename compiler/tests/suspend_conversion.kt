import kotlin.coroutines.*
suspend fun bar() = 1
fun run1(f: suspend () -> String): String {
    var r = ""
    f.startCoroutine(Continuation(EmptyCoroutineContext) { r = it.getOrThrow() })
    return r
}
fun run2(x: Int, f: suspend (Int) -> String): String {
    var r = ""
    f.startCoroutine(x, Continuation(EmptyCoroutineContext) { r = it.getOrThrow() })
    return r
}
fun main() {
    val plain: () -> String = { "plain" }
    println(run1(plain))
    val p2: (Int) -> String = { "n" + it }
    println(run2(4, p2))
    println(run2(5) { bar(); "lam" + it })
    val ext: suspend String.() -> Int = { length + bar() }
    ext.createCoroutine("abc", Continuation(EmptyCoroutineContext) { println("len " + it.getOrThrow()) }).resume(Unit)
}
