// Float: literals, arithmetic, promotion, conversions (saturating), printing, arrays, Any boxing.
value class F(val v: Float)
object Flag { var hit = false; fun mark() { hit = true } }
val early: Any = Flag
fun half(x: Float): Float = x / 2f
fun kind(x: Any) = when (x) { !is Float -> "!Float"; 0.0F -> "zero"; else -> "other" }
fun main() {
    val a = 1.1f
    println(a); println(a + 3f); println(half(5f)); println(1.0f / 3); println(a.toDouble()); println(2.5.toFloat())
    println(1e10f); println(-0.0f); println(Float.NaN); println(Float.MAX_VALUE); println(a < 2.0); println(1 + a)
    println(Float.NaN.toInt()); println(1e20f.toLong()); println((-1e20).toInt()); println(3.9f.toInt())
    val arr = FloatArray(3) { it * 0.5f }; println(arr[2]); println("v=$a")
    val x: Any = 0.0f
    println(x == -0.0f); println(kind(-0.0f)); println(F(0.0f) == F(-0.0f)); println(F(Float.NaN) == F(Float.NaN))
    println(a.hashCode()); println(a.compareTo(1.1f)); println(kotlin.math.abs(-2.5f))
    (early as Flag).mark(); println(Flag.hit)
}
