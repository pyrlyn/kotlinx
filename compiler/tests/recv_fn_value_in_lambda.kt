// `x.f()` with `f` a captured value of a receiver function type, called inside a lambda; generic constructors
// inferred from the expected type (`block(Controller("c"))`, `val c: C<String> = C()`).
class Controller<E>(val tag: String)

fun <E1> generate(block: Controller<E1>.() -> Unit) {
    block(Controller("c"))
}

class A(val r: String)

fun foo(c: Controller<String>): A = A("from " + c.tag)

fun runIt(f: () -> Unit) = f()

fun bar(propertyForInvoke: A.() -> Unit) {
    generate {
        foo(this).propertyForInvoke()
    }
}

fun plain(p: A.(Int) -> Unit) {
    runIt { A("x").p(2) }
}

fun main() {
    var result = "fail"
    bar { result = r }
    println(result)
    plain { n -> println(r + n) }
    val c: Controller<Int> = Controller("typed")
    println(c.tag)
}
