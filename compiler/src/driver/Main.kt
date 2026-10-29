@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package newkotlin

import kotlinx.cinterop.*
import platform.posix.*

private const val USAGE = """usage: newkotlinc <input.kt> -o <output> [options]
  --target <t>         native (default; LLVM, native binary), js, wasm, jvm (only native is implemented)
  --runtime <file.c>   C runtime to link (default: ${'$'}NEWKOTLIN_RUNTIME or compiler/runtime/runtime.c)
  --emit-llvm          also keep <output>.ll
  --cc <clang>         C compiler/linker (default: ${'$'}NK_CC, Apple clang, newest mise conda:clang, else clang)
  --cflags "<flags>"   optimization flags for clang (default: -O3 -flto)
  --opt <path>         LLVM opt run on the IR before linking (lto-pre-link<O3> with -flto, else default<O3>);
                       default: ${'$'}NK_OPT, opt on PATH, `xcrun --find opt`, ${'$'}CONDA_PREFIX/bin, Homebrew llvm
                       (llvm, then newest llvm@N), mise conda:llvm-tools and llvm installs
  --no-opt             skip the opt step (clang -O3 -flto still optimizes)
  --leak-check         count live runtime objects; report at exit, exit 3 on a leak
  --alloc <mimalloc|malloc|pool> object allocator: mimalloc (default; found via ${'$'}NEWKOTLIN_MIMALLOC_DIR,
                       pkg-config or Homebrew), system malloc/free, or free-list pool
  --gc <name>          memory manager, compiler/gc/<name>/gc.c (also --gc=<name>; default: ${'$'}NEWKOTLIN_GC or
                       default): default (RC + cycle collector), incremental (budgeted
                       cycle collector steps), rc (RC only, cycles leak), boehm (RC + bdwgc)
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
/** Major LLVM version from `<tool> --version` ("LLVM version 23.1.4", "clang version 21.0.0"), or null. */
private val majorCache = HashMap<String, Int?>()

/** Memoized; mise install paths (".../conda-llvm-tools/21.1.8/bin/opt") give it without running the tool (~0.15 s each). */
private fun majorVersion(tool: String): Int? = majorCache.getOrPut(tool) {
    Regex("/mise/installs/conda-(?:llvm-tools|clang)/(\\d+)[^/]*/bin/").find(tool)?.let { return@getOrPut it.groupValues[1].toInt() }
    majorVersionRun(tool)
}

private fun majorVersionRun(tool: String): Int? {
    val out = capture("'$tool' --version 2>/dev/null | grep -oE '(LLVM|clang) version [0-9]+' | head -1") ?: return null
    return out.substringAfterLast(' ').toIntOrNull()
}

/** All stdout lines of a shell command. */
private fun captureAll(cmd: String): List<String> {
    val p = popen(cmd, "r") ?: return emptyList()
    val out = StringBuilder()
    memScoped { val buf = allocArray<ByteVar>(512); while (fgets(buf, 512, p) != null) out.append(buf.toKString()) }
    pclose(p)
    return out.lines().map { it.trim() }.filter { it.isNotEmpty() }
}

/**
 * C compiler/linker when --cc is absent: ${'$'}NK_CC, then Apple clang (/usr/bin/clang, macOS), then the newest
 * mise `conda:clang`, else `clang`. Apple clang's LTO measured faster than conda clang 23 (matmul 10 vs 16 ms,
 * refpass 130 vs 148 ms) and its link step about 2x faster; findOpt then picks an opt its LLVM can read.
 */
private fun findCc(): String {
    getenv("NK_CC")?.toKString()?.let { return it }
    if (access("/usr/bin/clang", X_OK) == 0) capture("/usr/bin/clang --version 2>/dev/null")?.let { v ->
        if (v.startsWith("Apple")) { majorCache["/usr/bin/clang"] = Regex("version (\\d+)").find(v)?.groupValues?.get(1)?.toIntOrNull(); return "/usr/bin/clang" }
    }
    val home = getenv("HOME")?.toKString() ?: ""
    return captureAll("ls -d '$home'/.local/share/mise/installs/conda-clang/*/bin/clang 2>/dev/null | grep -v /latest/ | sort -rV")
        .firstOrNull { access(it, X_OK) == 0 } ?: "clang"
}

private fun findOpt(explicit: String?, cc: String): String? {
    (explicit ?: getenv("NK_OPT")?.toKString())?.let { return if (access(it, X_OK) == 0) it else null }
    // mise shims (also returned by xcrun, which searches PATH) cost ~0.4 s per call and pick the version by the
    // current directory; skip them and use the mise installs below.
    capture("command -v opt 2>/dev/null")?.let { if ("/mise/shims/" !in it && access(it, X_OK) == 0) return it }
    capture("xcrun --find opt 2>/dev/null")?.let { if ("/mise/shims/" !in it && access(it, X_OK) == 0) return it }
    // Common LLVM install locations: conda env, Homebrew llvm (unversioned, then newest llvm@N), mise.
    val home = getenv("HOME")?.toKString() ?: ""
    val fixed = listOfNotNull(getenv("CONDA_PREFIX")?.toKString()?.let { "$it/bin/opt" },
        "/opt/homebrew/opt/llvm/bin/opt", "/usr/local/opt/llvm/bin/opt")
    fixed.firstOrNull { access(it, X_OK) == 0 }?.let { return it }
    // mise `conda:llvm-tools` (the repo's mise.toml; `mise install`), newest version first.
    // Prefer the newest one the C compiler can read (LLVM major <= the C compiler's).
    val maxMajor = majorVersion(cc)
    val mise = captureAll("ls -d '$home'/.local/share/mise/installs/conda-llvm-tools/*/bin/opt 2>/dev/null | grep -v /latest/ | sort -rV")
    (mise.firstOrNull { o -> access(o, X_OK) == 0 && (maxMajor == null || (majorVersion(o) ?: 0) <= maxMajor) } ?: mise.firstOrNull { access(it, X_OK) == 0 })
        ?.let { return it }
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
    var gc = getenv("NEWKOTLIN_GC")?.toKString()?.takeIf { it.isNotEmpty() } ?: "default"
    var alloc = "mimalloc"; var leakCheck = false; var emitLl = false; var onlyIr = false; var ccArg: String? = null; var cflags = "-O3 -flto"; var optPath: String? = null; var noOpt = false
    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "-o" -> output = args[++i]
            "--target" -> target = Target.parse(args[++i]) ?: run { fprintf(stderr, "unknown target: %s\n", args[i]); exit(2); return }
            "--runtime" -> runtime = args[++i]
            "--gc" -> gc = args[++i]
            "--emit-llvm" -> emitLl = true
            "--leak-check" -> leakCheck = true
            "--alloc" -> alloc = args[++i].also { if (it != "mimalloc" && it != "malloc" && it != "pool") { fprintf(stderr, "--alloc must be mimalloc, malloc or pool\n"); exit(2) } }
            "--cc" -> ccArg = args[++i]
            "--cflags" -> cflags = args[++i]
            "--opt" -> optPath = args[++i]
            "--no-opt" -> noOpt = true
            "-S" -> onlyIr = true
            "-h", "--help" -> { println(USAGE); return }
            else -> if (a.startsWith("--gc=")) gc = a.substringAfter('=')
            else if (a.startsWith("--target=")) target = Target.parse(a.substringAfter('=')) ?: run { fprintf(stderr, "unknown target: %s\n", a); exit(2); return }
            else if (input == null && !a.startsWith("-")) input = a else { fprintf(stderr, "unknown argument: %s\n%s\n", a, USAGE); exit(2) }
        }
        i++
    }
    val cc = ccArg ?: findCc()
    if (!target.implemented) { fprintf(stderr, "target %s is not supported yet\n", target.flag); exit(1); return }
    if (input == null || output == null) { fprintf(stderr, "%s\n", USAGE); exit(2); return }
    val ir = try { compileToIr(readFile(input)) } catch (e: CompileError) {
        fprintf(stderr, "%s: error: %s\n", input, e.message); exit(1); return
    }
    if (onlyIr) { writeFile(output, ir); return }
    // GC implementations: <gcDir>/<name>/gc.c (+ optional config.sh printing extra clang flags), interface <gcDir>/kxgc.h.
    val gcDir = getenv("NEWKOTLIN_GC_DIR")?.toKString() ?: (runtime.substringBeforeLast('/', ".") + "/../gc")
    val gcSrc = "$gcDir/$gc/gc.c"
    if (gc.isEmpty() || '/' in gc || access(gcSrc, F_OK) != 0) {
        fprintf(stderr, "unknown --gc %s: no %s\n", gc, gcSrc); exit(2); return
    }
    val gcFlags = if (access("$gcDir/$gc/config.sh", F_OK) == 0) {
        capture("sh '$gcDir/$gc/config.sh'") ?: run { fprintf(stderr, "error: %s/%s/config.sh failed\n", gcDir, gc); exit(1); return }
    } else ""
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
    val tmps = ArrayList<String>()
    fun cleanup() { if (!emitLl) { remove(ll); tmps.forEach { remove(it) } } }
    if (!noOpt) {
        val opt = findOpt(optPath, cc) ?: run {
            cleanup()
            fprintf(stderr, "error: LLVM opt not found (or not executable). Pass --opt <path>, set NK_OPT, put opt on PATH, " +
                "install LLVM (e.g. brew install llvm, or mise install in the repo), or use --no-opt\n")
            exit(1); return
        }
        fun run1(c: String): Boolean { val r = system(c); if (r != 0) { cleanup(); fprintf(stderr, "command failed (%d): %s\n", r, c); exit(1) }; return r == 0 }
        val optMajor = majorVersion(opt); val ccMajor = majorVersion(cc)
        if (optMajor != null && ccMajor != null && optMajor > ccMajor) {
            // The C compiler's LLVM cannot read IR written by a newer opt (attribute syntax changes). Compiling the
            // Kotlin IR with the newer llc instead loses runtime LTO and measured 7-125% slower, so this is an error.
            cleanup()
            fprintf(stderr, "error: opt %s is LLVM %d, newer than %s (LLVM %d), which cannot read its IR. Use an opt of " +
                "LLVM <= %d (e.g. conda:llvm-tools %d.x via mise, or --opt/NK_OPT), or --no-opt\n", opt, optMajor, cc, ccMajor, ccMajor, ccMajor)
            exit(1); return
        }
        linkLl = "$output.opt.ll"; tmps.add(linkLl)
        // With LTO the link step runs the full O3 pipeline once; opt then runs the matching LTO pre-link pipeline
        // (a standalone default<O3> before LTO optimizes twice and was up to 2x slower on closures/dispatch/matmul).
        val pipeline = if ("-flto" in cflags) "lto-pre-link<O3>" else "default<O3>"
        run1("'$opt' -passes='$pipeline' -S '$ll' -o '$linkLl'")
    }
    val cmd = "$cc $cflags$def -Wno-override-module -I'$gcDir' $gcFlags '$linkLl' '$runtime' '$gcSrc'$link -o '$output'"
    val rc = system(cmd)
    cleanup()
    if (rc != 0) { fprintf(stderr, "clang failed (%d): %s\n", rc, cmd); exit(1) }
}
