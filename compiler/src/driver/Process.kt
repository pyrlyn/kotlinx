@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package newkotlin

import kotlinx.cinterop.*
import kxproc.kx_fa_addclose
import kxproc.kx_fa_adddup2
import kxproc.kx_fa_addopen
import kxproc.kx_fa_free
import kxproc.kx_fa_new
import kxproc.kx_is_dir
import kxproc.kx_mtime
import kxproc.kx_spawnp
import platform.posix.*

// Tools (clang, opt, llvm-link, javac, jar, minifiers) are started from an argument vector with posix_spawnp: no
// shell parses the command, so spaces, quotes, `$`, `;` or globs in paths and values reach the tool literally.
// Redirections are posix_spawn file actions; globbing, `mkdir -p`, `cp` and `cat > file` are Kotlin code below.

/** Where a child's stdout or stderr goes. */
internal sealed class Io {
    /** The driver's own stream. */
    object Inherit : Io()
    /** /dev/null. */
    object Null : Io()
    /** Collected into [Proc.output] (stdout and stderr share one pipe when both capture). */
    object Capture : Io()
    /** stderr only: wherever stdout goes (`2>&1`). */
    object Stdout : Io()
    /** Truncated and written. */
    class File(val path: String) : Io()
}

/** Exit code of a finished process (128 + signal when a signal ended it; 127 when it could not be started). */
internal class Proc(val status: Int, val output: String)

/** [argv] as one line for messages: each word shell-quoted only when it needs it, so it can be pasted into a shell. */
internal fun cmdline(argv: List<String>): String =
    argv.joinToString(" ") { w -> if (w.isNotEmpty() && w.all { it.isLetterOrDigit() || it in "-_./=:,+@%^" }) w else shq(w) }

private fun decodeWaitStatus(st: Int): Int = if (st and 0x7f == 0) (st shr 8) and 0xff else 128 + (st and 0x7f)

/**
 * Runs [argv] (argv[0] looked up on PATH unless it contains a `/`) with the driver's environment and stdin, and waits
 * for it. A program that cannot be started gives status 127, as a shell would.
 */
internal fun exec(argv: List<String>, stdout: Io = Io.Inherit, stderr: Io = Io.Inherit): Proc {
    require(argv.isNotEmpty()) { "empty command" }
    require(stdout !is Io.Stdout) { "stdout cannot be redirected to itself" }
    val capture = stdout is Io.Capture || stderr is Io.Capture
    return memScoped {
        val fds = allocArray<IntVar>(2)
        if (capture && pipe(fds) != 0) throw CompileError("pipe failed: ${strerror(errno)?.toKString()}")
        val readFd = if (capture) fds[0] else -1
        val writeFd = if (capture) fds[1] else -1
        val fa = kx_fa_new() ?: throw CompileError("posix_spawn_file_actions_init failed")
        try {
            fun redirect(io: Io, fd: Int) {
                when (io) {
                    Io.Inherit -> {}
                    Io.Null -> kx_fa_addopen(fa, fd, "/dev/null", O_WRONLY, 0)
                    Io.Capture -> kx_fa_adddup2(fa, writeFd, fd)
                    Io.Stdout -> kx_fa_adddup2(fa, 1, fd)
                    is Io.File -> kx_fa_addopen(fa, fd, io.path, O_WRONLY or O_CREAT or O_TRUNC, 420)
                }
            }
            redirect(stdout, 1)
            redirect(stderr, 2)
            if (capture) { kx_fa_addclose(fa, readFd); kx_fa_addclose(fa, writeFd) }
            val cargv = allocArrayOf(argv.map { it.cstr.ptr } + listOf(null))
            val pid = alloc<IntVar>()
            val rc = kx_spawnp(pid.ptr, argv[0], fa, cargv)
            if (capture) close(writeFd)
            if (rc != 0) {
                if (capture) close(readFd)
                if (stderr !is Io.Null && stderr !is Io.Capture) fprintf(platform.posix.stderr, "newkotlinc: cannot run %s: %s\n", argv[0], strerror(rc)?.toKString())
                return@memScoped Proc(127, "")
            }
            val out = ByteArrayBuilder()
            if (capture) {
                val buf = ByteArray(4096)
                while (true) {
                    val n = buf.usePinned { read(readFd, it.addressOf(0), buf.size.convert()) }.toInt()
                    if (n > 0) out.append(buf, n)
                    else if (n < 0 && errno == EINTR) continue
                    else break
                }
                close(readFd)
            }
            val st = alloc<IntVar>()
            while (waitpid(pid.value, st.ptr, 0) < 0) {
                if (errno != EINTR) throw CompileError("waitpid failed for ${argv[0]}: ${strerror(errno)?.toKString()}")
            }
            Proc(decodeWaitStatus(st.value), out.decode())
        } finally { kx_fa_free(fa) }
    }
}

private class ByteArrayBuilder {
    private var data = ByteArray(4096); private var size = 0
    fun append(b: ByteArray, n: Int) {
        if (size + n > data.size) data = data.copyOf(maxOf(data.size * 2, size + n))
        b.copyInto(data, size, 0, n); size += n
    }
    fun decode(): String = data.decodeToString(0, size)
    fun bytes(): ByteArray = data.copyOf(size)
}

/** Runs [argv]; [CompileError] naming the command when it exits non-zero. */
internal fun execChecked(argv: List<String>, stdout: Io = Io.Inherit, stderr: Io = Io.Inherit) {
    val r = exec(argv, stdout, stderr)
    if (r.status != 0) throw CompileError("command failed (${r.status}): ${cmdline(argv)}")
}

/** Non-empty trimmed stdout lines of [argv] (stderr discarded, or merged into stdout with [mergeStderr]), whatever its exit status. */
internal fun execLines(argv: List<String>, mergeStderr: Boolean = false): List<String> =
    exec(argv, Io.Capture, if (mergeStderr) Io.Stdout else Io.Null).output.lines().map { it.trim() }.filter { it.isNotEmpty() }

/** First non-empty stdout line of [argv] (stderr discarded), or null when there is none or it exits non-zero. */
internal fun execLine(argv: List<String>): String? {
    val r = exec(argv, Io.Capture, Io.Null)
    if (r.status != 0) return null
    return r.output.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
}

/** Whitespace-separated words of [s] (`--cflags`, config.sh output): no shell quoting is interpreted. */
internal fun words(s: String): List<String> = s.split(' ', '\t', '\n', '\r').filter { it.isNotEmpty() }

/**
 * argv prefix of a tool given as a string (`--cc`, NK_CC, NK_WASM_CC): an executable path is one word even when it
 * contains spaces; otherwise a command with arguments such as `zig cc -target wasm32-wasi` is split into [words].
 */
internal fun toolArgv(cmd: String): List<String> =
    if (access(cmd, X_OK) == 0 || cmd.none { it == ' ' || it == '\t' }) listOf(cmd) else words(cmd)

/** Path of [tool] on ${'$'}PATH as `command -v` prints it (dir + "/" + tool), or null; [tool] with a `/` is checked as is. */
internal fun onPath(tool: String): String? {
    if ('/' in tool) return tool.takeIf { access(it, X_OK) == 0 && kx_is_dir(it) == 0 }
    val path = getenv("PATH")?.toKString() ?: return null
    for (dir in path.split(':')) {
        val p = (dir.ifEmpty { "." }) + "/" + tool
        if (access(p, X_OK) == 0 && kx_is_dir(p) != 1) return p
    }
    return null
}

/** Entry names of directory [dir] without `.` and `..` (and, like a shell glob, without other dot files); empty when unreadable. */
internal fun listDir(dir: String): List<String> {
    val d = opendir(dir) ?: return emptyList()
    val names = ArrayList<String>()
    try {
        while (true) {
            val e = readdir(d) ?: break
            val n = e.pointed.d_name.toKString()
            if (!n.startsWith(".")) names.add(n)
        }
    } finally { closedir(d) }
    return names.sorted()
}

/** Paths `<dir>/<name><suffix>` that exist, for each entry name of [dir] accepted by [name] (the shell's `dir/<glob>suffix`). */
internal fun globPaths(dir: String, suffix: String, name: (String) -> Boolean = { true }): List<String> =
    listDir(dir).filter(name).map { "$dir/$it$suffix" }.filter { access(it, F_OK) == 0 }

/**
 * Version order of `sort -V`: runs of digits compare as numbers, everything else character by character
 * ("llvm@9" < "llvm@21", "21.1.8" < "23.1.4").
 */
internal val versionOrder: Comparator<String> = Comparator { a, b ->
    var i = 0; var j = 0
    while (i < a.length && j < b.length) {
        if (a[i].isDigit() && b[j].isDigit()) {
            var ie = i; while (ie < a.length && a[ie].isDigit()) ie++
            var je = j; while (je < b.length && b[je].isDigit()) je++
            val x = a.substring(i, ie).trimStart('0'); val y = b.substring(j, je).trimStart('0')
            if (x.length != y.length) return@Comparator x.length - y.length
            val c = x.compareTo(y); if (c != 0) return@Comparator c
            i = ie; j = je
        } else {
            if (a[i] != b[j]) return@Comparator a[i] - b[j]
            i++; j++
        }
    }
    (a.length - i) - (b.length - j)
}

/** Modification time of [path] in seconds, or -1. */
internal fun fileMtime(path: String): Long = kx_mtime(path)

/** `mkdir -p`: [path] and its missing parents; [CompileError] when one cannot be created. */
internal fun mkdirs(path: String) {
    if (path.isEmpty() || kx_is_dir(path) == 1) return
    val parent = path.trimEnd('/').substringBeforeLast('/', "")
    if (parent.isNotEmpty() && parent != path) mkdirs(parent)
    if (mkdir(path, 493.convert()) != 0 && !(errno == EEXIST && kx_is_dir(path) == 1))
        throw CompileError("cannot create directory $path: ${strerror(errno)?.toKString()}")
}

/** `rm -rf`: [path] and, for a directory, everything under it (symbolic links are removed, not followed). */
internal fun removeTree(path: String) {
    when (kx_is_dir(path)) {
        -1 -> return
        1 -> { for (n in listDirAll(path)) removeTree("$path/$n"); rmdir(path) }
        else -> unlink(path)
    }
}

private fun listDirAll(dir: String): List<String> {
    val d = opendir(dir) ?: return emptyList()
    val names = ArrayList<String>()
    try {
        while (true) {
            val n = (readdir(d) ?: break).pointed.d_name.toKString()
            if (n != "." && n != "..") names.add(n)
        }
    } finally { closedir(d) }
    return names
}

/** The bytes of [path]; [CompileError] when it cannot be read. */
internal fun readBytes(path: String): ByteArray {
    val f = fopen(path, "rb") ?: throw CompileError("cannot open $path")
    try {
        val out = ByteArrayBuilder(); val buf = ByteArray(65536)
        while (true) {
            val n = buf.usePinned { fread(it.addressOf(0), 1u, buf.size.convert(), f) }.toInt()
            if (n > 0) out.append(buf, n)
            if (n < buf.size) break
        }
        if (ferror(f) != 0) throw CompileError("cannot read $path")
        return out.bytes()
    } finally { fclose(f) }
}

/** Writes [b] to [path]; [CompileError] on a short write or a failing close. */
internal fun writeFileBytes(path: String, b: ByteArray) {
    val f = fopen(path, "wb") ?: throw CompileError("cannot write $path")
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

/** `cat srcs... > dst`. */
internal fun concatFiles(srcs: List<String>, dst: String) {
    val parts = srcs.map { readBytes(it) }
    val all = ByteArray(parts.sumOf { it.size }); var at = 0
    for (p in parts) { p.copyInto(all, at); at += p.size }
    writeFileBytes(dst, all)
}
