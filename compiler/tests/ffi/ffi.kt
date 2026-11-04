// FFI: `external fun` declarations are implemented in C (ffi.c), linked with `--link ffi.c`.
external fun ffi_add(a: Int, b: Int): Int
external fun ffi_mul_long(a: Long, b: Long): Long
external fun ffi_scale(x: Double, k: Double): Double
external fun ffi_is_even(x: Long): Boolean
external fun ffi_bump(by: Long): Unit
external fun ffi_counter(): Long

fun main() {
    println(ffi_add(40, 2))
    println(ffi_add(Int.MAX_VALUE, 1))
    println(ffi_mul_long(3000000000L, 3L))
    println(ffi_scale(1.5, 4.0))
    println(ffi_is_even(10L))
    println(ffi_is_even(7L))
    for (i in 1..10) ffi_bump(i.toLong())
    println(ffi_counter())
    // Round trip: Kotlin -> C -> Kotlin value fed back into C.
    var acc = 0
    for (i in 0 until 1000) acc = ffi_add(acc, i)
    println(acc)
}
