@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package newkotlin

import kotlinx.cinterop.*
import platform.posix.*

private const val USAGE = """usage: newkotlinc <input.kt> -o <output> [options]
  --runtime <file.c>   C runtime to link (default: ${'$'}NEWKOTLIN_RUNTIME or compiler/runtime/runtime.c)
  --emit-llvm          also keep <output>.ll
  --cc <clang>         C compiler/linker (default: clang)
  --cflags "<flags>"   optimization flags for clang (default: -O3 -flto)
  -S                   only write LLVM IR to <output>"""

private fun readFile(path: String): String {
    val f = fopen(path, "rb") ?: throw CompileError("cannot open $path")
    try {
        fseek(f, 0, SEEK_END); val n = ftell(f).toInt(); fseek(f, 0, SEEK_SET)
        val buf = ByteArray(n)
        if (n > 0) buf.usePinned { fread(it.addressOf(0), 1u, n.toULong(), f) }
        return buf.decodeToString()
    } finally { fclose(f) }
}

private fun writeFile(path: String, text: String) {
    val f = fopen(path, "wb") ?: throw CompileError("cannot write $path")
    val b = text.encodeToByteArray()
    if (b.isNotEmpty()) b.usePinned { fwrite(it.addressOf(0), 1u, b.size.toULong(), f) }
    fclose(f)
}

fun compileToIr(src: String): String = Gen(Parser(Lexer(src).lex()).program()).generate()

fun main(args: Array<String>) {
    var input: String? = null; var output: String? = null
    var runtime = getenv("NEWKOTLIN_RUNTIME")?.toKString() ?: "compiler/runtime/runtime.c"
    var emitLl = false; var onlyIr = false; var cc = "clang"; var cflags = "-O3 -flto"
    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "-o" -> output = args[++i]
            "--runtime" -> runtime = args[++i]
            "--emit-llvm" -> emitLl = true
            "--cc" -> cc = args[++i]
            "--cflags" -> cflags = args[++i]
            "-S" -> onlyIr = true
            "-h", "--help" -> { println(USAGE); return }
            else -> if (input == null && !a.startsWith("-")) input = a else { fprintf(stderr, "unknown argument: %s\n%s\n", a, USAGE); exit(2) }
        }
        i++
    }
    if (input == null || output == null) { fprintf(stderr, "%s\n", USAGE); exit(2); return }
    val ir = try { compileToIr(readFile(input)) } catch (e: CompileError) {
        fprintf(stderr, "%s: error: %s\n", input, e.message); exit(1); return
    }
    if (onlyIr) { writeFile(output, ir); return }
    val ll = "$output.ll"
    writeFile(ll, ir)
    val cmd = "$cc $cflags -Wno-override-module '$ll' '$runtime' -o '$output'"
    val rc = system(cmd)
    if (!emitLl) remove(ll)
    if (rc != 0) { fprintf(stderr, "clang failed (%d): %s\n", rc, cmd); exit(1) }
}
