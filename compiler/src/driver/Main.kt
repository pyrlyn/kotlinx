@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package newkotlin

import kotlinx.cinterop.*
import platform.posix.*

private const val USAGE = """usage: newkotlinc <input.kt> -o <output> [options]
  --target <t>         native (default; LLVM, native binary), js, wasm, jvm (only native is implemented)
  --runtime <file.c>   C runtime to link (default: ${'$'}NEWKOTLIN_RUNTIME or compiler/runtime/runtime.c)
  --emit-llvm          also keep <output>.ll
  --cc <clang>         C compiler/linker (default: clang)
  --cflags "<flags>"   optimization flags for clang (default: -O3 -flto)
  --leak-check         count live runtime objects; report at exit, exit 3 on a leak
  --alloc <mimalloc|malloc|pool> object allocator: mimalloc (default; found via ${'$'}NEWKOTLIN_MIMALLOC_DIR,
                       pkg-config or Homebrew), system malloc/free, or free-list pool
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

private fun hasLib(dir: String): Boolean =
    listOf("dylib", "so", "a").any { access("$dir/libmimalloc.$it", F_OK) == 0 }

private fun pkgConfigLibdir(): String? {
    val p = popen("pkg-config --variable=libdir mimalloc 2>/dev/null", "r") ?: return null
    val out = StringBuilder()
    memScoped {
        val buf = allocArray<ByteVar>(512)
        while (fgets(buf, 512, p) != null) out.append(buf.toKString())
    }
    pclose(p)
    return out.toString().trim().ifEmpty { null }
}

/** Directory with libmimalloc: ${'$'}NEWKOTLIN_MIMALLOC_DIR, pkg-config, then Homebrew/system paths. */
private fun findMimalloc(): String? {
    getenv("NEWKOTLIN_MIMALLOC_DIR")?.toKString()?.let { return if (hasLib(it)) it else null }
    pkgConfigLibdir()?.let { if (hasLib(it)) return it }
    return listOf("/opt/homebrew/opt/mimalloc/lib", "/usr/local/opt/mimalloc/lib", "/usr/local/lib", "/usr/lib")
        .firstOrNull { hasLib(it) }
}

fun compileToIr(src: String): String = NativeBackend(Parser(Lexer(src).lex()).program()).generate()

fun main(args: Array<String>) {
    var input: String? = null; var output: String? = null
    var runtime = getenv("NEWKOTLIN_RUNTIME")?.toKString() ?: "compiler/runtime/runtime.c"
    var target = Target.NATIVE
    var alloc = "mimalloc"; var leakCheck = false; var emitLl = false; var onlyIr = false; var cc = "clang"; var cflags = "-O3 -flto"
    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "-o" -> output = args[++i]
            "--target" -> target = Target.parse(args[++i]) ?: run { fprintf(stderr, "unknown target: %s\n", args[i]); exit(2); return }
            "--runtime" -> runtime = args[++i]
            "--emit-llvm" -> emitLl = true
            "--leak-check" -> leakCheck = true
            "--alloc" -> alloc = args[++i].also { if (it != "mimalloc" && it != "malloc" && it != "pool") { fprintf(stderr, "--alloc must be mimalloc, malloc or pool\n"); exit(2) } }
            "--cc" -> cc = args[++i]
            "--cflags" -> cflags = args[++i]
            "-S" -> onlyIr = true
            "-h", "--help" -> { println(USAGE); return }
            else -> if (a.startsWith("--target=")) target = Target.parse(a.substringAfter('=')) ?: run { fprintf(stderr, "unknown target: %s\n", a); exit(2); return }
            else if (input == null && !a.startsWith("-")) input = a else { fprintf(stderr, "unknown argument: %s\n%s\n", a, USAGE); exit(2) }
        }
        i++
    }
    if (!target.implemented) { fprintf(stderr, "target %s is not supported yet\n", target.flag); exit(1); return }
    if (input == null || output == null) { fprintf(stderr, "%s\n", USAGE); exit(2); return }
    val ir = try { compileToIr(readFile(input)) } catch (e: CompileError) {
        fprintf(stderr, "%s: error: %s\n", input, e.message); exit(1); return
    }
    if (onlyIr) { writeFile(output, ir); return }
    val ll = "$output.ll"
    writeFile(ll, ir)
    val def = (if (alloc == "pool") " -DRT_POOL" else "") + (if (leakCheck) " -DRT_LEAKCHECK" else "")
    val link = if (alloc == "mimalloc") {
        val dir = findMimalloc() ?: run {
            remove(ll)
            fprintf(stderr, "error: mimalloc not found (default --alloc mimalloc). Install it (brew install mimalloc), " +
                "set NEWKOTLIN_MIMALLOC_DIR to the directory containing libmimalloc, or use --alloc malloc\n")
            exit(1); return
        }
        " -L'$dir' -lmimalloc -Wl,-rpath,'$dir'"
    } else ""
    val cmd = "$cc $cflags$def -Wno-override-module '$ll' '$runtime'$link -o '$output'"
    val rc = system(cmd)
    if (!emitLl) remove(ll)
    if (rc != 0) { fprintf(stderr, "clang failed (%d): %s\n", rc, cmd); exit(1) }
}
