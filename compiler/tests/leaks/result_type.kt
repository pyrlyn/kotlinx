// kotlin.Result as a library class: success/failure, accessors, fold, runCatching; nullable type parameters
// instantiated with primitives (boxed), and `x as Int` on an `Int?`.
fun parse(s: String): Result<Int> = if (s.length < 3) Result.success(s.length) else Result.failure<Int>(IllegalArgumentException("long: " + s))

class Box<T>(val v: T?) {
    fun get(): T? = v
    companion object { fun <T> of(v: T): Box<T> = Box<T>(v) }
}

fun unbox(a: Int?): Int = a as Int

fun main() {
    val ok = parse("ab")
    println(ok.isSuccess)
    println(ok.getOrNull())
    println(ok.getOrThrow() + 1)
    println(ok)
    val bad = parse("abcd")
    println(bad.isFailure)
    println(bad.getOrNull())
    val ex = bad.exceptionOrNull()
    println(ex?.message)
    println(bad.getOrDefault(-1))
    println(bad.fold({ "v=$it" }, { "e=" + it.message }))
    try { bad.getOrThrow() } catch (e: IllegalArgumentException) { println("thrown " + e.message) }
    println(Result.success("s").getOrThrow())
    println(runCatching { 6 * 7 }.getOrNull())
    val boom = runCatching<Int> { throw IllegalStateException("boom") }.exceptionOrNull()
    println(boom?.message)
    println(Box.of(5).get())
    println(Box<Int>(null).get())
    println(unbox(9))
    try { unbox(null) } catch (e: NullPointerException) { println("npe") }
}
