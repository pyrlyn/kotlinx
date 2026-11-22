import kotlinx.coroutines.*

// Two classes on one line with a method of the same name: only A.f is suspend. Suspend-ness used to be looked up by
// source line and name, so the ordinary B.f was compiled as a coroutine too.
class A { suspend fun f(): Int { yield(); return 1 } }; class B { fun f(): Int = 2 }

suspend fun g(): Int = 3; fun h(): Int = 4

fun main() {
    println(B().f())
    println(h())
    runBlocking {
        println(A().f() + B().f() + g() + h())
    }
}
