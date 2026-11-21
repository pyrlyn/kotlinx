// A lambda capturing a nullable primitive keeps the null (it was unboxed on capture), and toString() on a
// nullable primitive yields "null".

fun captureInt(n: Int?): () -> String = { n.toString() }
fun captureBool(b: Boolean?): () -> String = { "" + b }
fun describe(d: Double?): String = d.toString()

fun main() {
    println(captureInt(7)() + " " + captureInt(null)())
    println(captureBool(true)() + " " + captureBool(null)())
    println(describe(1.5) + " " + describe(null))
}
