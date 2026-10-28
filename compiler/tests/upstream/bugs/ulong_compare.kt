// BUG: unsigned comparison of ULong compiled as signed.
// Expected: true    Actual: false
// Upstream: box/unsignedTypes/unsignedLiteralsForMaxLongValue.kt
fun main() {
    val maxULong = 0xFFFF_FFFF_FFFF_FFFFuL
    val zero = 0uL
    println(zero < maxULong)
}
