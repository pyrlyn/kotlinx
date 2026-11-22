@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package newkotlin

import kotlinx.cinterop.*
import platform.posix.*

// Host toolchain helpers shared by the native and js targets: files, clang and LLVM opt lookup.

/**
 * POSIX shell quoting of one word: [s] in single quotes, each embedded `'` written as `'\''`. The driver runs no
 * shell (tools are started from argv, see Process.kt); this only makes the commands in error messages ([cmdline])
 * safe to paste into one.
 */
internal fun shq(s: String): String = "'" + s.replace("'", "'\\''") + "'"

/** The whole file at [path] as UTF-8; [CompileError] when it cannot be opened, sized or fully read. */
internal fun readFile(path: String): String {
    val f = fopen(path, "rb") ?: throw CompileError("cannot open $path")
    try {
        if (fseek(f, 0, SEEK_END) != 0) throw CompileError("cannot seek $path")
        val size: Long = ftell(f).convert()
        if (size < 0) throw CompileError("cannot size $path")
        if (size > Int.MAX_VALUE - 8) throw CompileError("$path is too large ($size bytes)")
        if (fseek(f, 0, SEEK_SET) != 0) throw CompileError("cannot seek $path")
        val n = size.toInt()
        val buf = ByteArray(n)
        if (n > 0) {
            val got: Long = buf.usePinned { fread(it.addressOf(0), 1u, n.convert(), f) }.convert()
            if (got != size || ferror(f) != 0) throw CompileError("cannot read $path (read $got of $size bytes)")
        }
        return buf.decodeToString()
    } finally { fclose(f) }
}

/** Writes [text] to [path]; [CompileError] on a short write or a failing close (e.g. a full disk). */
internal fun writeFile(path: String, text: String) {
    val f = fopen(path, "wb") ?: throw CompileError("cannot write $path")
    val b = text.encodeToByteArray()
    var closed = false
    try {
        if (b.isNotEmpty()) {
            val put: Long = b.usePinned { fwrite(it.addressOf(0), 1u, b.size.convert(), f) }.convert()
            if (put != b.size.toLong()) throw CompileError("cannot write $path (wrote $put of ${b.size} bytes)")
        }
        closed = true
        if (fclose(f) != 0) throw CompileError("cannot write $path (close failed)")
    } finally { if (!closed) fclose(f) }
}

/** Major LLVM version from `<tool> --version` ("LLVM version 23.1.4", "clang version 21.0.0"), or null. */
internal val majorCache = HashMap<String, Int?>()

/** Memoized; mise install paths (".../conda-llvm-tools/21.1.8/bin/opt") give it without running the tool (~0.15 s each). */
internal fun majorVersion(tool: String): Int? = majorCache.getOrPut(tool) {
    Regex("/mise/installs/conda-(?:llvm-tools|clang)/(\\d+)[^/]*/bin/").find(tool)?.let { return@getOrPut it.groupValues[1].toInt() }
    majorVersionRun(tool)
}

/** [tool] may be a command with arguments (`zig cc -target wasm32-wasi`, see [toolArgv]). */
private fun majorVersionRun(tool: String): Int? =
    execLines(toolArgv(tool) + "--version").firstNotNullOfOrNull { Regex("(?:LLVM|clang) version (\\d+)").find(it) }
        ?.groupValues?.get(1)?.toIntOrNull()

/**
 * C compiler/linker when --cc is absent: ${'$'}NK_CC, then Apple clang (/usr/bin/clang, macOS), then the newest
 * mise `conda:clang`, else `clang`. Apple clang's LTO measured faster than conda clang 23 (matmul 10 vs 16 ms,
 * refpass 130 vs 148 ms) and its link step about 2x faster; findOpt then picks an opt its LLVM can read.
 */
internal fun findCc(): String {
    getenv("NK_CC")?.toKString()?.let { return it }
    if (access("/usr/bin/clang", X_OK) == 0) execLine(listOf("/usr/bin/clang", "--version"))?.let { v ->
        if (v.startsWith("Apple")) { majorCache["/usr/bin/clang"] = Regex("version (\\d+)").find(v)?.groupValues?.get(1)?.toIntOrNull(); return "/usr/bin/clang" }
    }
    val home = getenv("HOME")?.toKString() ?: ""
    return miseTools(home, "conda-clang", "clang").firstOrNull { access(it, X_OK) == 0 } ?: "clang"
}

/**
 * LLVM `opt`: --opt <path>, ${'$'}NK_OPT, `opt` on PATH, `xcrun --find opt`, then common install locations.
 * [maxMajor]: prefer a mise install no newer than this (the C compiler must read opt's IR). [minMajor]: skip
 * candidates older than this (the js target links Zig bitcode, which needs LLVM >= 22).
 */
internal fun findOpt(explicit: String?, maxMajor: Int?, minMajor: Int = 0): String? {
    fun ok(o: String) = access(o, X_OK) == 0 && (minMajor == 0 || (majorVersion(o) ?: 0) >= minMajor)
    explicit?.let { return if (access(it, X_OK) == 0) it else null }
    // NK_OPT usually pins the native target's opt to the C compiler's LLVM; one older than minMajor is skipped.
    getenv("NK_OPT")?.toKString()?.let { if (minMajor == 0 || ok(it)) return if (access(it, X_OK) == 0) it else null }
    // mise shims (also returned by xcrun, which searches PATH) cost ~0.4 s per call and pick the version by the
    // current directory; skip them and use the mise installs below.
    onPath("opt")?.let { if ("/mise/shims/" !in it && ok(it)) return it }
    if (onPath("xcrun") != null) execLine(listOf("xcrun", "--find", "opt"))?.let { if ("/mise/shims/" !in it && ok(it)) return it }
    // Common LLVM install locations: conda env, Homebrew llvm (unversioned, then newest llvm@N), mise.
    val home = getenv("HOME")?.toKString() ?: ""
    val fixed = listOfNotNull(getenv("CONDA_PREFIX")?.toKString()?.let { "$it/bin/opt" },
        "/opt/homebrew/opt/llvm/bin/opt", "/usr/local/opt/llvm/bin/opt")
    // Prefer one the C compiler can read (LLVM major <= the C compiler's).
    fixed.firstOrNull { ok(it) && (maxMajor == null || (majorVersion(it) ?: 0) <= maxMajor) }?.let { return it }
    // mise `conda:llvm-tools` (the repo's mise.toml; `mise install`), newest version first.
    val mise = miseTools(home, "conda-llvm-tools", "opt")
    (mise.firstOrNull { o -> ok(o) && (maxMajor == null || (majorVersion(o) ?: 0) <= maxMajor) } ?: mise.firstOrNull { ok(it) })
        ?.let { return it }
    // Newest of /opt/homebrew/opt/llvm@*/bin/opt, /usr/local/opt/llvm@*/bin/opt and mise llvm/*/bin/opt.
    val versioned = listOf("/opt/homebrew/opt", "/usr/local/opt").flatMap { d -> globPaths(d, "/bin/opt") { it.startsWith("llvm@") } } +
        globPaths("$home/.local/share/mise/installs/llvm", "/bin/opt")
    return versioned.maxWithOrNull(versionOrder)?.takeIf { ok(it) }
}

/** `<home>/.local/share/mise/installs/<plugin>/<version>/bin/<tool>` that exist, newest version first, without `latest`. */
private fun miseTools(home: String, plugin: String, tool: String): List<String> =
    globPaths("$home/.local/share/mise/installs/$plugin", "/bin/$tool") { it != "latest" }.sortedWith(versionOrder).reversed()
