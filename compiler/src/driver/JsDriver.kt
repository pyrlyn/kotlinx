@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package newkotlin

import kotlinx.cinterop.*
import platform.posix.*

/**
 * Collector for --target=js when none is given: compact (plain RC + a small synchronous cycle collector, written
 * for the JS cost model). epoch needs a background thread, which JS does not have.
 */
const val JS_DEFAULT_GC = "compact"

/** Oldest LLVM whose opt/llvm-link read the Zig 0.17.1 collector bitcode (LLVM 22). */
private const val JS_MIN_LLVM = 22

/**
 * `newkotlinc --target=js`: the program IR ([LlvmTarget.JS32]), compiler/runtime/runtime.c and
 * compiler/runtime/js/libc.c (both compiled to IR for wasm32-wasi, by default with `zig cc`) and the Zig collector's
 * wasm32 bitcode (native/gc/zig: `zig build gc-bc -Dtarget=wasm32-wasi`) are linked into one module, optimized once
 * by opt (vectors scalarized, nothing vectorized: JS has no SIMD) and translated to JavaScript by backend/js
 * (LLVM IR -> JS), with compiler/runtime/js/runtime.js in front. The result runs under node. wasm32 rather than the
 * host's 64-bit layout: pointers, `size_t` and `long` are one int32 in JavaScript, not two words.
 */
internal fun buildJs(ir: String, output: String, runtime: String, gc: String, gcDir: String, gcFlags: String, zigLibDir: String,
                     cc: String, optPath: String?, emitLl: Boolean) {
    fun fail(msg: String): Nothing { fprintf(stderr, "error: %s\n", msg); exit(1); throw IllegalStateException() }
    if (gc == "epoch") fail("--target=js: the epoch collector needs a background thread; use --gc compact (default for js), perceus, incremental or rc")
    val gcBc = "$zigLibDir/libgc-$gc.bc"
    if (access(gcBc, F_OK) != 0)
        fail("Zig GC bitcode missing: $gcBc\nbin/newkotlinc builds it; or run `zig build gc-bc -Dgc=$gc -Dtarget=wasm32-wasi -p $WASM_ZIG_PREFIX` " +
            "in native/gc/zig (Zig 0.17.1), or set NEWKOTLIN_GC_ZIG_WASM_LIB_DIR")
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
    // -O2 without vectorization (JS has no SIMD) and without debug info (zig cc emits it by default);
    // RT_PLAIN_MALLOC: libc.c's allocator, not mimalloc; RT_LEAN_EXIT: no GC statistics and no final collection;
    // RT_SIZE_CHECK: arrays of 2 GB or more fail (OutOfMemoryError) instead of wrapping around the 32-bit size_t;
    // RT_JS: number text, exception messages and rt_nanos come from runtime.js (no printf), no getrlimit.
    val cflags = "-S -emit-llvm -O2 -g0 -fno-vectorize -fno-slp-vectorize -DRT_PLAIN_MALLOC -DRT_LEAN_EXIT -DRT_SIZE_CHECK -DRT_JS -I'$gcDir' $gcFlags"
    run("$cc $cflags '$runtime' -o '$rtLl'")
    val rtIr = readFile(rtLl)
    // The program IR is wasm32's (LlvmTarget.JS32); runtime.c built for another target (--cc) would link into a
    // mismatched layout without an error.
    if (!Regex("""(?m)^target triple = "wasm32-""").containsMatchIn(rtIr)) {
        cleanup(); fail("--target=js needs a C compiler for wasm32-wasi (default `zig cc -target wasm32-wasi`, or \$NK_WASM_CC); `$cc` is not")
    }
    // Native integer widths 32 only (not n32:64): opt then keeps loop counters in i32 instead of widening them
    // to i64, which JS has to emulate with two words.
    writeFile(rtLl, rtIr.replaceFirst(Regex("""(?m)^(target datalayout = "[^"]*)-n32:64-"""), "$1-n32-"))
    run("$cc $cflags '$libc' -o '$libcLl'")
    // The C runtime first: its target datalayout (the program IR has only a triple) becomes the module's.
    run("'$link' -suppress-warnings '$rtLl' '$libcLl' '$ll' '$gcBc' -o '$linked'")
    // No loop unrolling: V8 tiers up and on-stack-replaces every copy of an unrolled loop separately (main runs once,
    // its loops reach optimized code through OSR), and the copies cost bundle size.
    run("'$opt' -passes='internalize,scalarizer<load-store>,default<O3>' --internalize-public-api-list=main " +
        "-vectorize-loops=false -vectorize-slp=false -disable-loop-unrolling -S '$linked' -o '$optLl'")
    // LL2JS_PRETTY=1: LLVM names and one statement per line, for reading and profiling the output.
    val compact = getenv("LL2JS_PRETTY")?.toKString().isNullOrEmpty()
    val js = try { JsBackend.translate(readFile(optLl), readFile(runtimeJs), compact) { fprintf(stderr, "warning: %s\n", it) } }
        catch (e: CompileError) { cleanup(); fail(e.message ?: "JS backend failed") }
    writeFile(output, js)
    cleanup()
}
