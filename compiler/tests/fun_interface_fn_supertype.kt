// Fun interfaces extending function types, and SAM conversion of function values that are not lambda literals.

fun interface Counter : () -> Int

fun interface Mapper<P> : (P) -> String

fun interface Sink<P> : suspend (P) -> Unit

// A plain interface may extend a function type too; its values are called like functions.
interface Labeler<P> : (P) -> String

class Brackets : Labeler<Int> {
    override fun invoke(p1: Int): String = "[$p1]"
}

fun label(f: (Int) -> String) = f(9)

fun interface Sink2<P> : suspend (P) -> Unit

class Holder<P>(val sink: Sink<P>)

fun <P> holder(sink: Sink2<P>): Holder<P> = Holder(sink)

fun interface Action {
    fun run()
}

fun interface Transform<T> {
    fun apply(x: T): T
}

typealias ActionAlias = Action
typealias IntTransform = Transform<Int>

class Box<T>(val v: T) {
    fun map(t: Transform<T>): Box<T> = Box(t.apply(v))
}

fun callTwice(a: Action) { a.run(); a.run() }

fun maybeRun(a: Action?): String {
    if (a == null) return "null"
    a.run()
    return "ran"
}

fun asAny(c: Counter): Any = c

fun invokeIt(f: () -> Int) = f()

fun runAll(vararg actions: Action) { for (a in actions) a.run() }

fun <T> applyTwice(t: Transform<T>, x: T): T = t.apply(t.apply(x))

fun hello() { println("hello from a reference") }

fun main() {
    // An object implementing a fun interface that extends `() -> Int`: it is also a function value.
    val c = object : Counter {
        override fun invoke(): Int = 42
        override fun toString(): String = "counter"
    }
    val any = asAny(c)
    println(c === any)
    println(any.toString())
    println(c.invoke())
    println(invokeIt(c))
    val c2 = Counter { 7 }
    println(c2.invoke() + invokeIt(c2))

    val m = Mapper<Int> { "m$it" }
    println(m.invoke(3))
    println("${c()} ${m(4)}")
    val l: Labeler<Int> = Brackets()
    println(l(1) + label(l) + label(Brackets()))

    // Suspend function supertype; Sink2 converts to Sink (a function value of the same type).
    println(Holder<Int> {}.sink !== null)
    println(holder<String> {}.sink !== null)

    // Function values (references, variables, nullable values) converted to a fun interface.
    var n = 0
    val f: () -> Unit = { n++ }
    callTwice(f)
    callTwice(::hello)
    println(n)
    val none: (() -> Unit)? = null
    println(maybeRun(none))
    println(maybeRun(f))
    println(maybeRun(null))
    runAll({ n += 10 }, Action { n += 100 })
    println(n)

    // SAM constructors through typealiases and with type arguments read from the lambda's parameter types.
    ActionAlias { println("alias") }.run()
    println(IntTransform { it * 3 }.apply(5))
    println(Transform { s: String -> s + "!" }.apply("hi"))

    // Explicit type arguments and a method whose fun-interface parameter uses the class's type parameter.
    println(applyTwice<Int>({ it + 1 }, 1))
    println(Box("a").map { it + "b" }.map { it + "c" }.v)
}
