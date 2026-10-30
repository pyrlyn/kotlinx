// Double constants.
fun main() {
    val n = Double.NaN
    println(n != n); println(Double.POSITIVE_INFINITY > 1e308); println(Double.NEGATIVE_INFINITY < -1e308)
    println(Double.MAX_VALUE > 1e308); println(Double.MIN_VALUE > 0.0); println(Double.MIN_VALUE / 2.0 == 0.0)
}
