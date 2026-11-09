@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package newkotlin

import kotlinx.cinterop.*
import platform.posix.*

/** Collector for --target=js when none is given: epoch needs a background thread, which JS does not have. */
const val JS_DEFAULT_GC = "incremental"

/** Oldest LLVM whose opt/llvm-link read the Zig 0.17.1 collector bitcode (LLVM 22). */
private const val JS_MIN_LLVM = 22

/**
 * `newkotlinc --target=js`: the program IR, compiler/runtime/runtime.c and compiler/runtime/js/libc.c (both compiled
 * to IR by clang) and the Zig collector's bitcode (native/gc/zig: `zig build gc-bc`) are linked into one module,
 * optimized once by opt (vectors scalarized, nothing vectorized: JS has no SIMD) and translated to JavaScript by
 * backend/js (LLVM IR -> JS), with compiler/runtime/js/runtime.js in front. The result runs under node.
 */
internal fun buildJs(ir: String, output: String, runtime: String, gc: String, gcDir: String, gcFlags: String, zigLibDir: String,
                     cc: String, optPath: String?, emitLl: Boolean) {
    fun fail(msg: String): Nothing { fprintf(stderr, "error: %s\n", msg); exit(1); throw IllegalStateException() }
    if (gc == "epoch") fail("--target=js: the epoch collector needs a background thread; use --gc incremental (default for js), perceus or rc")
    val gcBc = "$zigLibDir/libgc-$gc.bc"
    if (access(gcBc, F_OK) != 0)
        fail("Zig GC bitcode missing: $gcBc\nbin/newkotlinc builds it; or run `zig build gc-bc -Dgc=$gc` in native/gc/zig (Zig 0.17.1), or set NEWKOTLIN_GC_ZIG_LIB_DIR")
    val jsDir = runtime.substringBeforeLast('/', ".") + "/js"
    val libc = "$jsDir/libc.c"; val runtimeJs = "$jsDir/runtime.js"
    if (access(libc, F_OK) != 0 || access(runtimeJs, F_OK) != 0) fail("js runtime missing: $libc, $runtimeJs")
    val opt = findOpt(optPath, null, JS_MIN_LLVM)
        ?: fail("LLVM opt $JS_MIN_LLVM+ not found (the Zig GC bitcode needs it). Pass --opt <path>, set NK_OPT, or install LLVM (e.g. brew install llvm, mise install)")
    majorVersion(opt)?.let { if (it < JS_MIN_LLVM) fail("--target=js needs LLVM opt $JS_MIN_LLVM+ to read the Zig GC bitcode; $opt is LLVM $it") }
    val link = if ('/' in opt) opt.substringBeforeLast('/') + "/llvm-link" else "llvm-link"
    if ('/' in link && access(link, X_OK) != 0) fail("llvm-link not found next to $opt")

    val ll = "$output.ll"; val rtLl = "$output.rt.ll"; val libcLl = "$output.libc.ll"; val linked = "$output.linked.bc"
    val optLl = "$output.opt.ll"; val log = "$output.log"
    val tmps = listOf(rtLl, libcLl, linked, log) + (if (emitLl) emptyList() else listOf(ll, optLl))
    fun cleanup() { tmps.forEach { remove(it) } }
    /** Runs c with stderr to the log; on failure prints the log and exits. */
    fun run(c: String) {
        val r = system("$c 2>'$log'")
        if (r != 0) { fprintf(stderr, "%s\ncommand failed (%d): %s\n", (try { readFile(log) } catch (e: CompileError) { "" }), r, c); cleanup(); exit(1) }
    }
    // LLVM 22 made llvm.coro.end return void; NativeBackend still writes the older i1 form (the native target
    // pins opt to the C compiler's LLVM). Its result is never used.
    writeFile(ll, ir.replace("declare i1 @llvm.coro.end(", "declare void @llvm.coro.end(")
        .replace(Regex("""%[\w.]+ = call i1 @llvm\.coro\.end\("""), "call void @llvm.coro.end("))
    // -O2 without vectorization (JS has no SIMD); RT_PLAIN_MALLOC: libc.c's allocator, not mimalloc;
    // RT_LEAN_EXIT: no GC statistics and no final collection.
    val cflags = "-S -emit-llvm -O2 -fno-vectorize -fno-slp-vectorize -DRT_PLAIN_MALLOC -DRT_LEAN_EXIT -DRT_JS -I'$gcDir' $gcFlags"
    run("$cc $cflags '$runtime' -o '$rtLl'")
    // Native integer widths 32 only (not n32:64): opt then keeps loop counters in i32 instead of widening them
    // to i64, which JS has to emulate with two words.
    writeFile(rtLl, readFile(rtLl).replaceFirst(Regex("""(?m)^(target datalayout = "[^"]*)-n32:64-"""), "$1-n32-"))
    run("$cc $cflags '$libc' -o '$libcLl'")
    // The C runtime first: its target datalayout (the program IR has only a triple) becomes the module's.
    run("'$link' -suppress-warnings '$rtLl' '$libcLl' '$ll' '$gcBc' -o '$linked'")
    run("'$opt' -passes='internalize,scalarizer<load-store>,default<O3>' --internalize-public-api-list=main " +
        "-vectorize-loops=false -vectorize-slp=false -S '$linked' -o '$optLl'")
    val js = try { JsBackend.translate(readFile(optLl), readFile(runtimeJs)) { fprintf(stderr, "warning: %s\n", it) } }
        catch (e: CompileError) { cleanup(); fail(e.message ?: "JS backend failed") }
    writeFile(output, js)
    cleanup()
}
