// Unit as a value: parameters, type arguments, Result<Unit>, string conversion.
fun take(u: Unit): String = "got " + u
class Box<T>(val v: T)
fun <T> id(x: T): T = x
fun main() {
    println(take(Unit))
    val b = Box(Unit)
    println(b.v)
    val r: Result<Unit> = Result.success(Unit)
    println(r.isSuccess)
    println(r.getOrThrow())
    val f = Result.failure<Unit>(IllegalStateException("bad"))
    println(f.exceptionOrNull()?.message)
    println(id(Unit))
}
