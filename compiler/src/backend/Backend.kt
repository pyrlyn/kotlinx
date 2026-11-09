package newkotlin

/**
 * Compilation targets. The frontend (frontend/: lexer, parser, AST, types) is shared; each target has
 * its own backend module under backend/<target>/, except wasm: it is the native backend's LLVM IR for wasm32-wasi
 * (backend/native/LlvmTarget.kt), linked with the same runtime and collectors. The target is chosen once per compiler invocation
 * (`--target`), so generated code never contains any cross-target abstraction or indirection.
 */
enum class Target(val flag: String, val implemented: Boolean) {
    NATIVE("native", true),
    JS("js", true),
    WASM("wasm", true),
    JVM("jvm", true);

    companion object { fun parse(s: String): Target? = entries.firstOrNull { it.flag == s } }
}
