@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package newkotlin

import kotlinx.cinterop.*
import platform.posix.*

// Host toolchain helpers shared by the native and js targets: files, shell commands, clang and LLVM opt lookup.

internal fun readFile(path: String): String {
    val f = fopen(path, "rb") ?: throw CompileError("cannot open $path")
    try {
        fseek(f, 0, SEEK_END); val n = ftell(f).toInt(); fseek(f, 0, SEEK_SET)
        val buf = ByteArray(n)
        if (n > 0) buf.usePinned { fread(it.addressOf(0), 1u, n.toULong(), f) }
        return buf.decodeToString()
    } finally { fclose(f) }
}

internal fun writeFile(path: String, text: String) {
    val f = fopen(path, "wb") ?: throw CompileError("cannot write $path")
    val b = text.encodeToByteArray()
    if (b.isNotEmpty()) b.usePinned { fwrite(it.addressOf(0), 1u, b.size.toULong(), f) }
    fclose(f)
}

/** First line of a shell command's stdout, or null. */
internal fun capture(cmd: String): String? {
    val p = popen(cmd, "r") ?: return null
    val out = StringBuilder()
    memScoped {
        val buf = allocArray<ByteVar>(512)
        while (fgets(buf, 512, p) != null) out.append(buf.toKString())
    }
    pclose(p)
    return out.toString().trim().lineSequence().firstOrNull()?.trim()?.ifEmpty { null }
}

/** Major LLVM version from `<tool> --version` ("LLVM version 23.1.4", "clang version 21.0.0"), or null. */
private val majorCache = HashMap<String, Int?>()

/** Memoized; mise install paths (".../conda-llvm-tools/21.1.8/bin/opt") give it without running the tool (~0.15 s each). */
internal fun majorVersion(tool: String): Int? = majorCache.getOrPut(tool) {
    Regex("/mise/installs/conda-(?:llvm-tools|clang)/(\\d+)[^/]*/bin/").find(tool)?.let { return@getOrPut it.groupValues[1].toInt() }
    majorVersionRun(tool)
}

private fun majorVersionRun(tool: String): Int? {
    val out = capture("'$tool' --version 2>/dev/null | grep -oE '(LLVM|clang) version [0-9]+' | head -1") ?: return null
    return out.substringAfterLast(' ').toIntOrNull()
}

/** All stdout lines of a shell command. */
internal fun captureAll(cmd: String): List<String> {
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
internal fun findCc(): String {
    getenv("NK_CC")?.toKString()?.let { return it }
    if (access("/usr/bin/clang", X_OK) == 0) capture("/usr/bin/clang --version 2>/dev/null")?.let { v ->
        if (v.startsWith("Apple")) { majorCache["/usr/bin/clang"] = Regex("version (\\d+)").find(v)?.groupValues?.get(1)?.toIntOrNull(); return "/usr/bin/clang" }
    }
    val home = getenv("HOME")?.toKString() ?: ""
    return captureAll("ls -d '$home'/.local/share/mise/installs/conda-clang/*/bin/clang 2>/dev/null | grep -v /latest/ | sort -rV")
        .firstOrNull { access(it, X_OK) == 0 } ?: "clang"
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
    capture("command -v opt 2>/dev/null")?.let { if ("/mise/shims/" !in it && ok(it)) return it }
    capture("xcrun --find opt 2>/dev/null")?.let { if ("/mise/shims/" !in it && ok(it)) return it }
    // Common LLVM install locations: conda env, Homebrew llvm (unversioned, then newest llvm@N), mise.
    val home = getenv("HOME")?.toKString() ?: ""
    val fixed = listOfNotNull(getenv("CONDA_PREFIX")?.toKString()?.let { "$it/bin/opt" },
        "/opt/homebrew/opt/llvm/bin/opt", "/usr/local/opt/llvm/bin/opt")
    fixed.firstOrNull { ok(it) }?.let { return it }
    // mise `conda:llvm-tools` (the repo's mise.toml; `mise install`), newest version first.
    // Prefer the newest one the C compiler can read (LLVM major <= the C compiler's).
    val mise = captureAll("ls -d '$home'/.local/share/mise/installs/conda-llvm-tools/*/bin/opt 2>/dev/null | grep -v /latest/ | sort -rV")
    (mise.firstOrNull { o -> ok(o) && (maxMajor == null || (majorVersion(o) ?: 0) <= maxMajor) } ?: mise.firstOrNull { ok(it) })
        ?.let { return it }
    val globs = "/opt/homebrew/opt/llvm@*/bin/opt /usr/local/opt/llvm@*/bin/opt '$home'/.local/share/mise/installs/llvm/*/bin/opt"
    return capture("ls -d $globs 2>/dev/null | sort -V | tail -1")?.takeIf { ok(it) }
}
