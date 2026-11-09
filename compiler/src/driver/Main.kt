@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package newkotlin

import kotlinx.cinterop.*
import platform.posix.*

private const val USAGE = """usage: newkotlinc <input.kt> -o <output> [options]
  --target <t>         native (default; LLVM, native binary), js (JavaScript for node, via LLVM IR), wasm (same
                       LLVM IR for wasm32-wasi, linked with ${'$'}NK_WASM_CC or `zig cc -target wasm32-wasi`; GC
                       perceus, --alloc malloc; Zig GC archives from native/gc/zig/zig-out/wasm32-wasi/lib or
                       ${'$'}NEWKOTLIN_GC_ZIG_WASM_LIB_DIR), jvm (not implemented)
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
  --gc <name>          collector name (also --gc=<name>; default: ${'$'}NEWKOTLIN_GC or epoch):
                       epoch (deferred RC + background cycle collection; alias "default"),
                       perceus (RC + synchronous cycle collector), incremental (budgeted
                       cycle steps), compact (RC + small synchronous cycle collector, made for
                       --target=js), rc (RC only, cycles leak). Caps/metadata from native/gc/c/<name>/.
                       --target=js: compact by default; epoch is not available (no threads).
  --gc-runtime <zig|c> collector implementation language (also --gc-runtime=; default:
                       ${'$'}NEWKOTLIN_GC_RUNTIME or zig). zig links native/gc/zig/zig-out/lib/libgc-<name>.a
                       (experimental Zig port); c links native/gc/c/<name>/gc.c. Only the collector
                       backend changes; compiler/runtime/runtime.c is always linked.
  --link <arg>         extra linker input for `external fun` (FFI): a .c/.o/.a file, -l<lib>, -L<dir> or a
                       linker flag; repeatable (also --link=<arg>)
  -S                   only write LLVM IR to <output>"""

private fun hasLib(dir: String): Boolean =
    listOf("dylib", "so", "a").any { access("$dir/libmimalloc.$it", F_OK) == 0 }

private fun pkgConfigLibdir(): String? = capture("pkg-config --variable=libdir mimalloc 2>/dev/null")

/** Directory with libmimalloc: ${'$'}NEWKOTLIN_MIMALLOC_DIR, pkg-config, then Homebrew/system paths. */
private fun findMimalloc(): String? {
    getenv("NEWKOTLIN_MIMALLOC_DIR")?.toKString()?.let { return if (hasLib(it)) it else null }
    pkgConfigLibdir()?.let { if (hasLib(it)) return it }
    return listOf("/opt/homebrew/opt/mimalloc/lib", "/usr/local/opt/mimalloc/lib", "/usr/local/lib", "/usr/lib")
        .firstOrNull { hasLib(it) }
}

/** Source text to LLVM IR: lex, parse, merge the prelude and `Any` support, then generate code. */
/** `suspend fun main()`: renamed and run by a generated `fun main() = runBlocking { ... }` (same line numbers). */
private val SUSPEND_MAIN = Regex("""(?m)^suspend\s+fun\s+main\s*\(\s*\)""")
fun compileToIr(src00: String): String {
    val src0 = if (SUSPEND_MAIN.containsMatchIn(src00)) SUSPEND_MAIN.replace(src00, "suspend fun kxSuspendMain()") + "\nfun main() = runBlocking { kxSuspendMain() }\n" else src00
    val src = withSerializable(src0); return compileGenerics(withAny(src, withPrelude(src, Parser(Lexer(src).lex()).program()))) }

/** Collector used when neither --gc nor NEWKOTLIN_GC is given (and what --gc=default means). */
const val DEFAULT_GC = "epoch"

/** Collector for --target=wasm when none is given: synchronous cycle collection, no threads. */
const val WASM_GC = "perceus"

/** Install prefix of the wasm32-wasi Zig GC archives, relative to native/gc/zig (`zig build gc-libs -p ...`). */
const val WASM_ZIG_PREFIX = "zig-out/wasm32-wasi"

/** The `newkotlinc` command; see docs/usage.md for the options. */
fun main(args: Array<String>) {
    var input: String? = null; var output: String? = null
    var runtime = getenv("NEWKOTLIN_RUNTIME")?.toKString() ?: "compiler/runtime/runtime.c"
    var target = Target.NATIVE
    if (getenv("NEWKOTLIN_NO_BORROW_FIELD_LOCALS")?.toKString()?.let { it.isNotEmpty() && it != "0" } == true) borrowFieldLocals = false
    var gc = getenv("NEWKOTLIN_GC")?.toKString()?.takeIf { it.isNotEmpty() }
    var gcRuntime = getenv("NEWKOTLIN_GC_RUNTIME")?.toKString()?.takeIf { it.isNotEmpty() }
    var alloc: String? = null; var leakCheck = false; var emitLl = false; var onlyIr = false
    var ccArg: String? = null; var cflags = "-O3 -flto"; var optPath: String? = null; var noOpt = false
    val links = ArrayList<String>()
    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "-o" -> output = args[++i]
            "--target" -> target = Target.parse(args[++i]) ?: run { fprintf(stderr, "unknown target: %s\n", args[i]); exit(2); return }
            "--runtime" -> runtime = args[++i]
            "--gc" -> gc = args[++i]
            "--gc-runtime" -> gcRuntime = args[++i]
            "--emit-llvm" -> emitLl = true
            "--leak-check" -> leakCheck = true
            "--alloc" -> alloc = args[++i].also { if (it != "mimalloc" && it != "malloc" && it != "pool") { fprintf(stderr, "--alloc must be mimalloc, malloc or pool\n"); exit(2) } }
            "--cc" -> ccArg = args[++i]
            "--cflags" -> cflags = args[++i]
            "--opt" -> optPath = args[++i]
            "--no-opt" -> noOpt = true
            "--link" -> links.add(args[++i])
            "-S" -> onlyIr = true
            "-h", "--help" -> { println(USAGE); return }
            else -> if (a.startsWith("--gc=")) gc = a.substringAfter('=')
            else if (a.startsWith("--gc-runtime=")) gcRuntime = a.substringAfter('=')
            else if (a.startsWith("--link=")) links.add(a.substringAfter('='))
            else if (a.startsWith("--target=")) target = Target.parse(a.substringAfter('=')) ?: run { fprintf(stderr, "unknown target: %s\n", a); exit(2); return }
            else if (input == null && !a.startsWith("-")) input = a else { fprintf(stderr, "unknown argument: %s\n%s\n", a, USAGE); exit(2) }
        }
        i++
    }
    val wasm = target == Target.WASM
    // wasm32-wasi has no threads (epoch collects in the background); js uses the compact collector.
    if (gc == null) gc = if (wasm) WASM_GC else if (target == Target.JS) JS_DEFAULT_GC else DEFAULT_GC
    if (gc == "default") gc = DEFAULT_GC // "default" names whatever the default collector is (epoch); old one: perceus
    if (gcRuntime == null) gcRuntime = "zig"
    if (alloc == null) alloc = if (wasm) "malloc" else "mimalloc"
    if (wasm) {
        llvmTarget = LlvmTarget.WASM32
        val bad = when {
            alloc == "mimalloc" -> "--target=wasm supports --alloc malloc or pool"
            gc == DEFAULT_GC -> "--target=wasm has no threads for the $gc collector; use --gc perceus, incremental or rc"
            links.isNotEmpty() -> "--target=wasm does not support --link"
            else -> null
        }
        if (bad != null) { fprintf(stderr, "%s\n", bad); exit(2); return }
    }
    if (gcRuntime != "zig" && gcRuntime != "c") {
        fprintf(stderr, "--gc-runtime must be zig or c (got %s)\n", gcRuntime); exit(2); return
    }
    val cc = ccArg ?: if (wasm) (getenv("NK_WASM_CC")?.toKString() ?: "zig cc -target wasm32-wasi") else findCc()
    // A compiler command with arguments (`zig cc ...`): its LLVM version picks the opt below.
    if (' ' in cc) majorCache[cc] = capture("$cc --version 2>/dev/null | grep -oE 'clang version [0-9]+' | head -1")?.substringAfterLast(' ')?.toIntOrNull()
    if (!target.implemented) { fprintf(stderr, "target %s is not supported yet\n", target.flag); exit(1); return }
    if (input == null || output == null) { fprintf(stderr, "%s\n", USAGE); exit(2); return }
    // A GC whose caps file lists "deferred" needs deferred-RC code generation (see NativeBackend.deferredRc).
    run {
        val dir = getenv("NEWKOTLIN_GC_DIR")?.toKString() ?: (runtime.substringBeforeLast('/', ".") + "/../../native/gc/c")
        val caps = if (access("$dir/$gc/caps", F_OK) == 0) (try { readFile("$dir/$gc/caps") } catch (e: CompileError) { "" }) else ""
        deferredRc = caps.split(Regex("\\s+")).contains("deferred")
    }
    val ir = try { compileToIr(readFile(input)) } catch (e: CompileError) {
        fprintf(stderr, "%s: error: %s\n", input, e.message); exit(1); return
    }
    if (onlyIr) { writeFile(output, ir); return }
    // GC: caps/metadata and C sources under <gcDir>; Zig static libs under <zigLibDir>/libgc-<name>.a.
    // Only the collector backend is zig|c; runtime.c is always the shared KotlinX runtime.
    val gcDir = getenv("NEWKOTLIN_GC_DIR")?.toKString() ?: (runtime.substringBeforeLast('/', ".") + "/../../native/gc/c")
    // wasm archives live under their own prefix so they never replace the host ones in zig-out/lib.
    val zigLibDir = getenv(if (wasm) "NEWKOTLIN_GC_ZIG_WASM_LIB_DIR" else "NEWKOTLIN_GC_ZIG_LIB_DIR")?.toKString()
        ?: (runtime.substringBeforeLast('/', ".") + "/../../native/gc/zig/" + (if (wasm) WASM_ZIG_PREFIX else "zig-out") + "/lib")
    if (gc.isEmpty() || '/' in gc) {
        fprintf(stderr, "unknown --gc %s\n", gc); exit(2); return
    }
    val gcCapsPath = "$gcDir/$gc/caps"
    if (access(gcCapsPath, F_OK) != 0 && access("$gcDir/$gc/gc.c", F_OK) != 0) {
        fprintf(stderr, "unknown --gc %s: no %s/ or %s/gc.c\n", gc, gcDir, gc); exit(2); return
    }
    val gcFlags = if (access("$gcDir/$gc/config.sh", F_OK) == 0) {
        capture("sh '$gcDir/$gc/config.sh'") ?: run { fprintf(stderr, "error: %s/%s/config.sh failed\n", gcDir, gc); exit(1); return }
    } else ""
    if (target == Target.JS) {
        if (gcRuntime != "zig") { fprintf(stderr, "--target=js links the Zig collector (--gc-runtime=zig)\n"); exit(2); return }
        if (leakCheck || links.isNotEmpty()) { fprintf(stderr, "--target=js does not support --leak-check or --link\n"); exit(2); return }
        buildJs(ir, output, runtime, gc, gcDir, gcFlags, zigLibDir, cc, optPath, emitLl)
        return
    }
    val gcObj = if (gcRuntime == "c") {
        val gcSrc = "$gcDir/$gc/gc.c"
        if (access(gcSrc, F_OK) != 0) {
            fprintf(stderr, "unknown --gc %s: no %s\n", gc, gcSrc); exit(2); return
        }
        "'$gcSrc'"
    } else {
        val lib = "$zigLibDir/libgc-$gc.a"
        if (access(lib, F_OK) != 0) {
            fprintf(stderr, "error: Zig GC archive missing: %s\n" +
                "Direct newkotlinc.kexe does not rebuild libs. Use bin/newkotlinc (runs zig build gc-libs), or\n" +
                "cd native/gc/zig && zig build gc-libs%s  (Zig 0.17.1), or set %s, or --gc-runtime=c\n", lib,
                if (wasm) " -Dtarget=wasm32-wasi -p $WASM_ZIG_PREFIX" else "",
                if (wasm) "NEWKOTLIN_GC_ZIG_WASM_LIB_DIR" else "NEWKOTLIN_GC_ZIG_LIB_DIR")
            exit(1); return
        }
        // libgc is LLVM machine code: Clang LTO cannot inspect a Zig archive.
        // Keep native code opaque and optimize the generated program only. wasm-ld (lld) links LTO bitcode
        // with a machine-code archive, so wasm keeps -flto for the program and runtime.c.
        if (!wasm && "-flto" in cflags) cflags = cflags.replace("-flto", "")
        "'$lib'"
    }
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
        val opt = findOpt(optPath, majorVersion(cc)) ?: run {
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
                "LLVM <= %d (e.g. conda:llvm-tools %d.x via mise, or --opt/NK_OPT), or --no-opt\n", opt, optMajor, cc, ccMajor, ccMajor)
            exit(1); return
        }
        linkLl = "$output.opt.ll"; tmps.add(linkLl)
        // With LTO the link step runs the full O3 pipeline once; opt then runs the matching LTO pre-link pipeline
        // (a standalone default<O3> before LTO optimizes twice and was up to 2x slower on closures/dispatch/matmul).
        val pipeline = if ("-flto" in cflags) "lto-pre-link<O3>" else "default<O3>"
        run1("'$opt' -passes='$pipeline' -S '$ll' -o '$linkLl'")
    }
    // wasm: deep recursion lives on the engine stack; the linear-memory shadow stack holds only address-taken
    // locals. 8 MiB matches the native default; -s drops the names section.
    val wasmLd = if (wasm) " -s -Wl,-z,stack-size=8388608" else ""
    val cmd = "$cc $cflags$def$wasmLd -Wno-override-module -I'$gcDir' $gcFlags '$linkLl' '$runtime' $gcObj$link${links.joinToString("") { " '$it'" }} -o '$output'"
    val rc = system(cmd)
    cleanup()
    if (rc != 0) { fprintf(stderr, "clang failed (%d): %s\n", rc, cmd); exit(1) }
}
