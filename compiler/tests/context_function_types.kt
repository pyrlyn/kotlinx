// Function types with context parameters: `context(A) () -> R` lambdas provide their context to calls inside.
class A { fun foo(): String = "class fun" }
class Logger(val prefix: String)
fun <C, R> context(c: C, block: context(C) () -> R): R = block(c)
context(a: A)
fun foo(): String = "context fun"
context(l: Logger)
fun log(msg: String) { println(l.prefix + msg) }
context(l: Logger, a: A)
fun both(): String = l.prefix + foo()
fun withTwo(block: context(Logger, A) () -> String): String = block(Logger("2> "), A())
fun withContext(f: context(String) () -> String) = f("OK")
fun callWithContext(f: (String) -> String) = withContext(f)
fun main() {
    println(A().foo())
    println(context(A()) { foo() })
    context(Logger("> ")) { log("hello"); log("world") }
    println(withTwo { both() })
    println(callWithContext { s -> s })
}
