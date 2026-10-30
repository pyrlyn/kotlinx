// Double constants; data class equals compares Double properties like Double.equals.
data class T(val d: Double)
fun main() {
    val n = Double.NaN
    println(n != n); println(Double.POSITIVE_INFINITY > 1e308); println(Double.NEGATIVE_INFINITY < -1e308)
    println(Double.MAX_VALUE > 1e308); println(Double.MIN_VALUE > 0.0); println(Double.MIN_VALUE / 2.0 == 0.0)
    println(T(Double.NaN) == T(Double.NaN)); println(T(0.0) == T(-0.0)); println(T(1.5) == T(1.5))
}
