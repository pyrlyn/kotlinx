package newkotlin

/** Wasm target: not implemented yet. The driver rejects `--target=wasm` before reaching this module. */
class WasmBackend(@Suppress("unused") private val prog: Program) {
    fun generate(): String = throw CompileError("target wasm is not supported yet")
}
