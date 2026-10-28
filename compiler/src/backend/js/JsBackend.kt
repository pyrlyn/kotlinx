package newkotlin

/** Js target: not implemented yet. The driver rejects `--target=js` before reaching this module. */
class JsBackend(@Suppress("unused") private val prog: Program) {
    fun generate(): String = throw CompileError("target js is not supported yet")
}
