@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package newkotlin

import kotlinx.cinterop.*
import platform.posix.*

private const val USAGE = """usage: newkotlinc <input.kt> -o <output> [options]
  --target <t>         native (default; LLVM, native binary), js (JavaScript for node, via wasm32 LLVM IR; Zig GC
                       bitcode from the wasm32-wasi lib directory below), wasm (same LLVM IR for wasm32-wasi,
                       linked with ${'$'}NK_WASM_CC or `zig cc -target wasm32-wasi`; GC perceus, --alloc pool; Zig GC
                       archives from native/gc/zig/zig-out/wasm32-wasi/lib or ${'$'}NEWKOTLIN_GC_ZIG_WASM_LIB_DIR),
                       jvm (executable jar; LLVM IR translated to JVM
                       bytecode, needs java 21+; GC perceus, --gc-runtime c, --alloc pool)
  --runtime <file.c>   C runtime to link (default: ${'$'}NEWKOTLIN_RUNTIME or compiler/runtime/runtime.c)
  --emit-llvm          also keep <output>.ll
  --minify             --target=js: minify the output with esbuild or bun (whichever is on PATH; NK_MINIFIER)
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

private fun pkgConfigLibdir(): String? =
    if (onPath("pkg-config") == null) null else execLine(listOf("pkg-config", "--variable=libdir", "mimalloc"))

/** Directory with libmimalloc: ${'$'}NEWKOTLIN_MIMALLOC_DIR, pkg-config, then Homebrew/system paths (incl. Debian multiarch). */
private fun findMimalloc(): String? {
    getenv("NEWKOTLIN_MIMALLOC_DIR")?.toKString()?.let { return if (hasLib(it)) it else null }
    pkgConfigLibdir()?.let { if (hasLib(it)) return it }
    return listOf("/opt/homebrew/opt/mimalloc/lib", "/usr/local/opt/mimalloc/lib", "/usr/local/lib", "/usr/lib",
        "/usr/lib/x86_64-linux-gnu", "/usr/lib/aarch64-linux-gnu", "/usr/lib64")
        .firstOrNull { hasLib(it) }
}

/** Collector used when neither --gc nor NEWKOTLIN_GC is given (and what --gc=default means). */
const val DEFAULT_GC = "epoch"

/** Collector for --target=wasm when none is given: synchronous cycle collection, no threads. */
const val WASM_GC = "perceus"

/** Install prefix of the wasm32-wasi Zig GC archives and bitcode, relative to native/gc/zig (`zig build gc-libs -p ...`,
 *  `zig build gc-bc -p ...` for --target=js). */
const val WASM_ZIG_PREFIX = "zig-out/wasm32-wasi"

/** Options from the command line only (environment defaults are applied by [main]); built by [parseArgs]. */
data class Options(
    val input: String? = null, val output: String? = null,
    val runtime: String? = null, val target: Target = Target.NATIVE,
    /** `--gc` / `--gc=` (null: ${'$'}NEWKOTLIN_GC or the target's default). */ val gc: String? = null,
    /** `--gc-runtime` / `--gc-runtime=` (null: not given on the command line). */ val gcRuntime: String? = null,
    val alloc: String? = null, val leakCheck: Boolean = false, val emitLl: Boolean = false, val onlyIr: Boolean = false,
    val minify: Boolean = false, val cc: String? = null, val cflags: String = "-O3 -flto", val opt: String? = null,
    val noOpt: Boolean = false, val links: List<String> = emptyList(), val help: Boolean = false,
)

/** A bad command line: [main] prints the message and the usage text and exits 2. */
class UsageError(msg: String) : Exception(msg)

private val ALLOCS = listOf("mimalloc", "malloc", "pool")

/**
 * Parses the command line. Every option that takes a value checks that one follows (`-o` at the end is a usage error,
 * not an index out of bounds); unknown options, a second input file and bad `--target` / `--alloc` values are
 * [UsageError]s.
 */
fun parseArgs(args: Array<String>): Options {
    var o = Options()
    val links = ArrayList<String>()
    var i = 0
    fun value(opt: String): String {
        if (i + 1 >= args.size) throw UsageError("option $opt needs a value")
        return args[++i]
    }
    fun target(t: String) = Target.parse(t) ?: throw UsageError("unknown target: $t (native, js, wasm or jvm)")
    fun alloc(a: String) = a.also { if (it !in ALLOCS) throw UsageError("--alloc must be mimalloc, malloc or pool (got $it)") }
    while (i < args.size) {
        val a = args[i]
        o = when {
            a == "-o" -> o.copy(output = value(a))
            a == "--target" -> o.copy(target = target(value(a)))
            a.startsWith("--target=") -> o.copy(target = target(a.substringAfter('=')))
            a == "--runtime" -> o.copy(runtime = value(a))
            a == "--gc" -> o.copy(gc = value(a))
            a.startsWith("--gc=") -> o.copy(gc = a.substringAfter('='))
            a == "--gc-runtime" -> o.copy(gcRuntime = value(a))
            a.startsWith("--gc-runtime=") -> o.copy(gcRuntime = a.substringAfter('='))
            a == "--emit-llvm" -> o.copy(emitLl = true)
            a == "--minify" -> o.copy(minify = true)
            a == "--leak-check" -> o.copy(leakCheck = true)
            a == "--alloc" -> o.copy(alloc = alloc(value(a)))
            a == "--cc" -> o.copy(cc = value(a))
            a == "--cflags" -> o.copy(cflags = value(a))
            a == "--opt" -> o.copy(opt = value(a))
            a == "--no-opt" -> o.copy(noOpt = true)
            a == "--link" -> { links.add(value(a)); o }
            a.startsWith("--link=") -> { links.add(a.substringAfter('=')); o }
            a == "-S" -> o.copy(onlyIr = true)
            a == "-h" || a == "--help" -> o.copy(help = true)
            a.startsWith("-") && a != "-" -> throw UsageError("unknown option: $a")
            o.input == null -> o.copy(input = a)
            else -> throw UsageError("unexpected argument: $a (input already given: ${o.input})")
        }
        i++
    }
    return o.copy(links = links)
}

/**
 * [cflags] without the LTO flags (`-flto`, `-flto=thin`, `-flto=full`, `-flto-jobs=N`), as whole words: a Zig collector
 * archive is machine code, which clang's LTO cannot read. A plain substring replace turned `-flto=thin` into `=thin`.
 */
fun stripLto(cflags: String): String = cflags.split(' ', '\t').filter { it.isNotEmpty() && it != "-flto" && !it.startsWith("-flto=") && !it.startsWith("-flto-") }.joinToString(" ")

/** Whether [cflags] asks for LTO (a `-flto` or `-flto=<kind>` word, not a substring of another flag). */
fun hasLto(cflags: String): Boolean = cflags.split(' ', '\t').any { it == "-flto" || it.startsWith("-flto=") }

private fun usageExit(msg: String): Nothing {
    fprintf(stderr, "newkotlinc: %s\n%s\n", msg, USAGE); exit(2); throw IllegalStateException()
}

/** The `newkotlinc` command; see docs/usage.md for the options. I/O failures of the driver ([CompileError]) exit 1. */
fun main(args: Array<String>) {
    try { compile(args) } catch (e: CompileError) { fprintf(stderr, "newkotlinc: error: %s\n", e.message); exit(1) }
}

/** Sets [hostTriple] for the machine the driver runs on (the IR used to name arm64 macOS everywhere). */
@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
private fun detectHostTriple() {
    val os = when (Platform.osFamily) { OsFamily.MACOSX -> "macos"; OsFamily.LINUX -> "linux"; else -> return }
    val arch = when (Platform.cpuArchitecture) { CpuArchitecture.ARM64 -> "arm64"; CpuArchitecture.X64 -> "x86_64"; else -> return }
    hostTripleFor(os, arch)?.let { hostTriple = it }
}

private fun compile(args: Array<String>) {
    val o = try { parseArgs(args) } catch (e: UsageError) { usageExit(e.message ?: "bad arguments") }
    detectHostTriple()
    if (o.help) { println(USAGE); return }
    val input = o.input; val output = o.output
    val runtime = o.runtime ?: getenv("NEWKOTLIN_RUNTIME")?.toKString() ?: "compiler/runtime/runtime.c"
    val target = o.target
    if (getenv("NEWKOTLIN_NO_BORROW_FIELD_LOCALS")?.toKString()?.let { it.isNotEmpty() && it != "0" } == true) borrowFieldLocals = false
    var gc = o.gc ?: getenv("NEWKOTLIN_GC")?.toKString()?.takeIf { it.isNotEmpty() }
    // --gc-runtime given on the command line (bin/newkotlinc always exports NEWKOTLIN_GC_RUNTIME)
    val gcRuntimeSet = o.gcRuntime != null
    var gcRuntime = o.gcRuntime ?: getenv("NEWKOTLIN_GC_RUNTIME")?.toKString()?.takeIf { it.isNotEmpty() }
    var alloc = o.alloc; val leakCheck = o.leakCheck; val emitLl = o.emitLl; val onlyIr = o.onlyIr; val minify = o.minify
    var cflags = o.cflags; val optPath = o.opt; val noOpt = o.noOpt
    val links = o.links
    val wasm = target == Target.WASM
    if (target == Target.JVM) {
        // The collector is translated with the program: C sources only, and no collector threads (epoch has one).
        if (gcRuntimeSet && gcRuntime != "c") { fprintf(stderr, "--target jvm needs --gc-runtime c\n"); exit(2); return }
        gcRuntime = "c"
        if (gc == null) gc = "perceus"
        if (alloc == null) alloc = "pool"
        if (alloc == "mimalloc") { fprintf(stderr, "--target jvm supports --alloc pool or malloc\n"); exit(2); return }
    }
    // js: the program, runtime.c and the collector are compiled for wasm32 (4-byte pointers and size_t).
    val wasm32 = wasm || target == Target.JS
    if (target == Target.JS) llvmTarget = LlvmTarget.JS32
    // wasm32-wasi has no threads (epoch collects in the background); js uses the compact collector.
    // js without --gc / NEWKOTLIN_GC: compact, or rc when the program allocates no possibly cyclic object (below).
    val jsAutoGc = gc == null && target == Target.JS
    if (gc == null) gc = if (wasm) WASM_GC else if (target == Target.JS) JS_DEFAULT_GC else DEFAULT_GC
    if (gc == "default") gc = DEFAULT_GC // "default" names whatever the default collector is (epoch); old one: perceus
    if (gcRuntime == null) gcRuntime = "zig"
    // wasm: the size-class pool, not wasi-libc malloc, which was up to 2x slower on allocation-heavy programs.
    if (alloc == null) alloc = if (wasm) "pool" else "mimalloc"
    if (wasm) {
        llvmTarget = LlvmTarget.WASM32
        val bad = when {
            alloc == "mimalloc" -> "--target=wasm supports --alloc pool or malloc"
            gc == DEFAULT_GC -> "--target=wasm has no threads for the $gc collector; use --gc perceus, incremental or rc"
            links.isNotEmpty() -> "--target=wasm does not support --link"
            else -> null
        }
        if (bad != null) { fprintf(stderr, "%s\n", bad); exit(2); return }
    }
    if (gcRuntime != "zig" && gcRuntime != "c") {
        fprintf(stderr, "--gc-runtime must be zig or c (got %s)\n", gcRuntime); exit(2); return
    }
    val cc = o.cc ?: if (wasm32) (getenv("NK_WASM_CC")?.toKString() ?: "zig cc -target wasm32-wasi") else findCc()
    // A compiler command with arguments (`zig cc ...`) is split into words (toolArgv); majorVersion runs it the same way.
    val ccArgv = toolArgv(cc)
    if (!target.implemented) { fprintf(stderr, "target %s is not supported yet\n", target.flag); exit(1); return }
    if (input == null) usageExit("no input file")
    if (output == null) usageExit("no output file (-o <output>)")
    // GC: caps/metadata and C sources under <gcDir>; Zig static libs under <zigLibDir>/libgc-<name>.a.
    // Only the collector backend is zig|c; runtime.c is always the shared KotlinX runtime.
    val gcDir = getenv("NEWKOTLIN_GC_DIR")?.toKString() ?: (runtime.substringBeforeLast('/', ".") + "/../../native/gc/c")
    // The collector name is validated before its caps are read (it is a path component).
    fun checkGc(name: String): Boolean {
        if (name.isEmpty() || '/' in name || name == "." || name == "..") { fprintf(stderr, "unknown --gc %s\n", name); exit(2); return false }
        if (access("$gcDir/$name/caps", F_OK) != 0 && access("$gcDir/$name/gc.c", F_OK) != 0) {
            fprintf(stderr, "unknown --gc %s: no %s/%s/caps or %s/%s/gc.c (set NEWKOTLIN_GC_DIR or use bin/newkotlinc)\n", name, gcDir, name, gcDir, name)
            exit(2); return false
        }
        return true
    }
    if (!checkGc(gc)) return
    // A GC whose caps file lists "deferred" needs deferred-RC code generation (see NativeBackend.deferredRc).
    run {
        val caps = if (access("$gcDir/$gc/caps", F_OK) == 0) (try { readFile("$gcDir/$gc/caps") } catch (e: CompileError) { "" }) else ""
        deferredRc = caps.split(Regex("\\s+")).contains("deferred")
    }
    val ir = try { compileToIr(readFile(input)) } catch (e: CompileError) {
        fprintf(stderr, "%s: error: %s\n", input, e.message); exit(1); return
    }
    if (onlyIr) { writeFile(output, ir); return }
    // Same IR for compact and rc (neither defers RC); only the linked collector differs.
    if (jsAutoGc && !allocatesCyclic(ir)) { gc = JS_ACYCLIC_GC; if (!checkGc(gc)) return }
    // wasm32 archives (wasm) and bitcode (js) live under their own prefix so they never replace the host ones in zig-out/lib.
    val zigLibDir = getenv(if (wasm32) "NEWKOTLIN_GC_ZIG_WASM_LIB_DIR" else "NEWKOTLIN_GC_ZIG_LIB_DIR")?.toKString()
        ?: (runtime.substringBeforeLast('/', ".") + "/../../native/gc/zig/" + (if (wasm32) WASM_ZIG_PREFIX else "zig-out") + "/lib")
    val gcFlags = if (access("$gcDir/$gc/config.sh", F_OK) == 0) {
        exec(listOf("sh", "$gcDir/$gc/config.sh"), Io.Capture).takeIf { it.status == 0 }
            ?.output?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
            ?: run { fprintf(stderr, "error: %s/%s/config.sh failed\n", gcDir, gc); exit(1); return }
    } else ""
    if (target == Target.JS) {
        if (gcRuntime != "zig") { fprintf(stderr, "--target=js links the Zig collector (--gc-runtime=zig)\n"); exit(2); return }
        if (leakCheck || links.isNotEmpty()) { fprintf(stderr, "--target=js does not support --leak-check or --link\n"); exit(2); return }
        buildJs(ir, output, runtime, gc, gcDir, gcFlags, zigLibDir, cc, optPath, emitLl, minify)
        return
    }
    val gcObjPath = if (gcRuntime == "c") {
        val gcSrc = "$gcDir/$gc/gc.c"
        if (access(gcSrc, F_OK) != 0) {
            fprintf(stderr, "unknown --gc %s: no %s\n", gc, gcSrc); exit(2); return
        }
        gcSrc
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
        if (!wasm && hasLto(cflags)) cflags = stripLto(cflags)
        lib
    }
    val defs = listOfNotNull(if (alloc == "pool") "-DRT_POOL" else null, if (alloc == "malloc") "-DRT_PLAIN_MALLOC" else null,
        if (leakCheck) "-DRT_LEAKCHECK" else null)
    if (target == Target.JVM) {
        val opt = findOpt(optPath, majorVersion(cc)) ?: run { fprintf(stderr, "error: LLVM opt not found (--opt <path> or NK_OPT); the jvm target needs it\n"); exit(1); return }
        try { linkJvm(ir, output, runtime, gcObjPath, defs + "-I$gcDir" + words(gcFlags), links, cc, opt, emitLl) }
        catch (e: CompileError) { fprintf(stderr, "%s: error: %s\n", input, e.message); exit(1) }
        return
    }
    val ll = "$output.ll"
    writeFile(ll, if (wasm) wasmKeepHotCalls(ir) else ir)
    val link = if (alloc == "mimalloc") {
        val dir = findMimalloc() ?: run {
            remove(ll)
            fprintf(stderr, "error: mimalloc not found (default --alloc mimalloc). Install it (brew install mimalloc), " +
                "set NEWKOTLIN_MIMALLOC_DIR to the directory containing libmimalloc, or use --alloc malloc\n")
            exit(1); return
        }
        listOf("-L$dir", "-lmimalloc", "-Wl,-rpath,$dir")
    } else emptyList()
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
        fun run1(argv: List<String>) { val r = exec(argv).status; if (r != 0) { cleanup(); fprintf(stderr, "command failed (%d): %s\n", r, cmdline(argv)); exit(1) } }
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
        val pipeline = if (hasLto(cflags)) "lto-pre-link<O3>" else "default<O3>"
        run1(listOf(opt, "-passes=$pipeline", "-S", ll, "-o", linkLl))
        if (wasm) outlineWasmLoops(opt, linkLl, output, tmps)?.let { linkLl = it }
    }
    // wasm: deep recursion lives on the engine stack; the linear-memory shadow stack holds only address-taken
    // locals. 8 MiB matches the native default; -s drops the names section.
    val wasmLd = if (wasm) listOf("-s", "-Wl,-z,stack-size=8388608") else emptyList()
    val cmd = ccArgv + words(cflags) + defs + wasmLd + listOf("-Wno-override-module", "-I$gcDir") + words(gcFlags) +
        listOf(linkLl, runtime, gcObjPath) + link + links + listOf("-o", output)
    val rc = exec(cmd).status
    cleanup()
    if (rc != 0) { fprintf(stderr, "clang failed (%d): %s\n", rc, cmdline(cmd)); exit(1) }
}

/**
 * wasm: outlines the inner loops of the run-once entry ([wasmOutlineLoopBlocks]) from the optimized program [ll];
 * returns the new IR file, or null when there is nothing to outline or a step fails (the program stays as it was).
 */
private fun outlineWasmLoops(opt: String, ll: String, output: String, tmps: MutableList<String>): String? {
    val named = "$output.named.ll"; val blocks = "$output.blocks"; val out = "$output.outlined.ll"
    tmps.add(named); tmps.add(blocks); tmps.add(out)
    val info = execLines(listOf(opt, "-passes=instnamer,print<loops>", "-S", ll, "-o", named), mergeStderr = true)
    val lines = wasmOutlineLoopBlocks(info)
    if (lines.isEmpty()) return null
    writeFile(blocks, lines.joinToString("\n", postfix = "\n"))
    val before = definedFunctions(readFile(named))
    if (exec(listOf(opt, "-passes=extract-blocks", "-extract-blocks-file=$blocks", "-S", named, "-o", out), stderr = Io.Null).status != 0) return null
    writeFile(out, wasmMarkOutlined(readFile(out), before) ?: return null)
    return out
}
