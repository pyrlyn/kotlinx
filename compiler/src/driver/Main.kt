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
  --opt <path>         LLVM opt run on the IR before linking (lto-pre-link<O3> with -flto, else default<O3>);
                       default: ${'$'}NK_OPT, opt on PATH, `xcrun --find opt`, ${'$'}CONDA_PREFIX/bin, Homebrew llvm
                       (llvm, then newest llvm@N), mise llvm installs
  --no-opt             skip the opt step (clang -O3 -flto still optimizes)
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

private fun pkgConfigLibdir(): String? = capture("pkg-config --variable=libdir mimalloc 2>/dev/null")

/** First line of a shell command's stdout, or null. */
private fun capture(cmd: String): String? {
    val p = popen(cmd, "r") ?: return null
    val out = StringBuilder()
    memScoped {
        val buf = allocArray<ByteVar>(512)
        while (fgets(buf, 512, p) != null) out.append(buf.toKString())
    }
    pclose(p)
    return out.toString().trim().lineSequence().firstOrNull()?.trim()?.ifEmpty { null }
}

/** LLVM `opt`: --opt <path>, ${'$'}NK_OPT, `opt` on PATH, `xcrun --find opt`, then common install locations. */
private fun findOpt(explicit: String?): String? {
    (explicit ?: getenv("NK_OPT")?.toKString())?.let { return if (access(it, X_OK) == 0) it else null }
    capture("command -v opt 2>/dev/null")?.let { if (access(it, X_OK) == 0) return it }
    capture("xcrun --find opt 2>/dev/null")?.let { if (access(it, X_OK) == 0) return it }
    // Common LLVM install locations: conda env, Homebrew llvm (unversioned, then newest llvm@N), mise.
    val home = getenv("HOME")?.toKString() ?: ""
    val fixed = listOfNotNull(getenv("CONDA_PREFIX")?.toKString()?.let { "$it/bin/opt" },
        "/opt/homebrew/opt/llvm/bin/opt", "/usr/local/opt/llvm/bin/opt")
    fixed.firstOrNull { access(it, X_OK) == 0 }?.let { return it }
    val globs = "/opt/homebrew/opt/llvm@*/bin/opt /usr/local/opt/llvm@*/bin/opt '$home'/.local/share/mise/installs/llvm/*/bin/opt"
    return capture("ls -d $globs 2>/dev/null | sort -V | tail -1")?.takeIf { access(it, X_OK) == 0 }
}

/** Directory with libmimalloc: ${'$'}NEWKOTLIN_MIMALLOC_DIR, pkg-config, then Homebrew/system paths. */
private fun findMimalloc(): String? {
    getenv("NEWKOTLIN_MIMALLOC_DIR")?.toKString()?.let { return if (hasLib(it)) it else null }
    pkgConfigLibdir()?.let { if (hasLib(it)) return it }
    return listOf("/opt/homebrew/opt/mimalloc/lib", "/usr/local/opt/mimalloc/lib", "/usr/local/lib", "/usr/lib")
        .firstOrNull { hasLib(it) }
}

fun compileToIr(src: String): String = compileGenerics(withAny(src, withPrelude(src, Parser(Lexer(src).lex()).program())))

fun main(args: Array<String>) {
    var input: String? = null; var output: String? = null
    var runtime = getenv("NEWKOTLIN_RUNTIME")?.toKString() ?: "compiler/runtime/runtime.c"
    var target = Target.NATIVE
    var alloc = "mimalloc"; var leakCheck = false; var emitLl = false; var onlyIr = false; var cc = "clang"; var cflags = "-O3 -flto"; var optPath: String? = null; var noOpt = false
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
            "--opt" -> optPath = args[++i]
            "--no-opt" -> noOpt = true
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
    val def = (if (alloc == "pool") " -DRT_POOL" else "") + (if (alloc == "malloc") " -DRT_PLAIN_MALLOC" else "") + (if (leakCheck) " -DRT_LEAKCHECK" else "")
    val link = if (alloc == "mimalloc") {
        val dir = findMimalloc() ?: run {
            remove(ll)
            fprintf(stderr, "error: mimalloc not found (default --alloc mimalloc). Install it (brew install mimalloc), " +
                "set NEWKOTLIN_MIMALLOC_DIR to the directory containing libmimalloc, or use --alloc malloc\n")
            exit(1); return
        }
        " -L'$dir' -lmimalloc -Wl,-rpath,'$dir'"
    } else ""
    var linkLl = ll
    if (!noOpt) {
        val opt = findOpt(optPath) ?: run {
            if (!emitLl) remove(ll)
            fprintf(stderr, "error: LLVM opt not found (or not executable). Pass --opt <path>, set NK_OPT, put opt on PATH, " +
                "install LLVM (e.g. brew install llvm), or use --no-opt\n")
            exit(1); return
        }
        linkLl = "$output.opt.ll"
        // With LTO the link step runs the full O3 pipeline once; opt then runs the matching LTO pre-link pipeline
        // (a standalone default<O3> before LTO optimizes twice and was up to 2x slower on closures/dispatch/matmul).
        val pipeline = if ("-flto" in cflags) "lto-pre-link<O3>" else "default<O3>"
        val ocmd = "'$opt' -passes='$pipeline' -S '$ll' -o '$linkLl'"
        val orc = system(ocmd)
        if (orc != 0) { if (!emitLl) remove(ll); remove(linkLl); fprintf(stderr, "opt failed (%d): %s\n", orc, ocmd); exit(1); return }
    }
    val cmd = "$cc $cflags$def -Wno-override-module '$linkLl' '$runtime'$link -o '$output'"
    val rc = system(cmd)
    if (!emitLl) remove(ll)
    if (linkLl != ll && !emitLl) remove(linkLl)
    if (rc != 0) { fprintf(stderr, "clang failed (%d): %s\n", rc, cmd); exit(1) }
}
