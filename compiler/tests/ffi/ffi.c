// C side of the FFI test (compiler/tests/ffi/ffi.kt).
#include <stdbool.h>
#include <stdint.h>
int32_t ffi_add(int32_t a, int32_t b) { return a + b; }
int64_t ffi_mul_long(int64_t a, int64_t b) { return a * b; }
double ffi_scale(double x, double k) { return x * k; }
bool ffi_is_even(int64_t x) { return (x & 1) == 0; }
static int64_t counter;
void ffi_bump(int64_t by) { counter += by; }
int64_t ffi_counter(void) { return counter; }
