package newkotlin

/**
 * Machine the LLVM IR emitted by [NativeBackend] is for. `--target=native`, `--target=wasm` and `--target=js` share
 * all of the code generation, the runtime (`runtime/runtime.c`) and the collectors (`native/gc/c`); only what differs
 * between the C ABIs lives here.
 *
 * - [ptrBytes]: object layout is computed by LLVM from [dataLayout]; the few hard-coded byte offsets (vtable slots,
 *   fields behind the header) are derived from this.
 * - [cSize] / [cLong]: LLVM type of C `size_t` / `long` for the libc functions in [LIBC_SIGS]. Kotlin code always
 *   sees `Long` (i64); [NativeBackend] converts at the call (no conversion where the type is i64).
 * - [entry]: symbol of `main(argc, argv)`; wasi-libc's `_start` calls `__main_argc_argv`.
 *
 * [JS32] is wasm32's C ABI (driver/JsDriver.kt compiles runtime.c and the collector for wasm32-wasi) with native
 * integer width 32 only, so opt keeps loop counters in i32 rather than i64, which JavaScript emulates with two words.
 */
enum class LlvmTarget(val triple: String, val dataLayout: String?, val ptrBytes: Int, val cSize: String, val cLong: String, val entry: String) {
    HOST("arm64-apple-macosx14.0.0", null, 8, "i64", "i64", "main"),
    WASM32("wasm32-unknown-wasi", "e-m:e-p:32:32-p10:8:8-p20:8:8-i64:64-i128:128-n32:64-S128-ni:1:10:20", 4, "i32", "i32", "__main_argc_argv"),
    JS32("wasm32-unknown-wasi", "e-m:e-p:32:32-p10:8:8-p20:8:8-i64:64-i128:128-n32-S128-ni:1:10:20", 4, "i32", "i32", "main");

    /** LLVM type of a C signature slot ("size" = size_t, "long" = long, else the LLVM type itself). */
    fun cType(t: String): String = when (t) { "size" -> cSize; "long" -> cLong; else -> t }

    /** `target datalayout` / `target triple` lines that start the module. */
    fun header(): String = (dataLayout?.let { "target datalayout = \"$it\"\n" } ?: "") + "target triple = \"$triple\"\n"
}

/** Target of the current compilation (set once by the driver, like [deferredRc]). */
var llvmTarget = LlvmTarget.HOST

/** libc functions whose C signature uses `size_t` / `long`: name to (return, parameters). Declared from this table. */
val LIBC_SIGS: Map<String, Pair<String, List<String>>> = mapOf(
    "malloc" to ("ptr" to listOf("size")),
    "strlen" to ("size" to listOf("ptr")),
    "fseek" to ("i32" to listOf("ptr", "long", "i32")),
    "ftell" to ("long" to listOf("ptr")),
    "fread" to ("size" to listOf("ptr", "size", "size", "ptr")),
)
