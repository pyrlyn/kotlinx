package newkotlin

/** Jvm target: not implemented yet. The driver rejects `--target=jvm` before reaching this module. */
class JvmBackend(@Suppress("unused") private val prog: Program) {
    fun generate(): String = throw CompileError("target jvm is not supported yet")
}
