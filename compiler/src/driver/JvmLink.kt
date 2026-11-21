@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package newkotlin

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.*
import newkotlin.jvm.JvmBackend

/*
 * `--target jvm` link step. The program IR, compiler/runtime/runtime.c, the collector's gc.c and
 * compiler/runtime/jvm/libc.c are compiled to LLVM bitcode, linked (llvm-link), optimized as one module (opt), and
 * translated to one JVM class by JvmBackend. The Java runtime (compiler/runtime/jvm/KxRt.java) is compiled once
 * with javac and cached next to the compiler build. The output is an executable jar: a short `sh` prefix that runs
 * `java -jar "$0"`, followed by the jar itself (zip readers locate the archive from its end).
 */

/** Class name of the translated program inside the jar. */
private const val MAIN_CLASS = "Main"

/**
 * JVM options of the launcher prefix (sh words, quoted where the shell could glob). No tiered-JIT size limit
 * (translated functions can be large); the program's own methods are compiled after 1/20 of the usual invocation
 * and loop counts (a translated method is hot from its first call, and short programs otherwise run mostly
 * interpreted); `quiet` keeps the CompileCommand banner off stdout.
 */
private const val JVM_OPTS = "-XX:-DontCompileHugeMethods -XX:CompileCommand=quiet " +
    "'-XX:CompileCommand=CompileThresholdScaling,$MAIN_CLASS::*,0.05'"

private fun sh(cmd: String, cleanup: () -> Unit) {
    val r = system(cmd)
    if (r != 0) { cleanup(); throw CompileError("command failed ($r): $cmd") }
}

private fun writeBytes(path: String, b: ByteArray) {
    val f = fopen(path, "wb") ?: throw CompileError("cannot write $path")
    if (b.isNotEmpty()) b.usePinned { fwrite(it.addressOf(0), 1u, b.size.toULong(), f) }
    fclose(f)
}

private fun mtime(path: String): Long = capture("stat -f %m '$path' 2>/dev/null")?.toLongOrNull() ?: -1

/** Names of the C functions KxRt.java provides (`public static <type> name(`). */
private fun runtimeFunctions(src: String): Set<String> =
    Regex("""public static (?:final )?[\w.\[\]]+ (\w+)\(""").findAll(src).map { it.groupValues[1] }.toSet()

/** Tool next to [opt] (same LLVM install), else on PATH. */
private fun sibling(opt: String, tool: String): String {
    val p = opt.substringBeforeLast('/', "") + "/" + tool
    return if (opt.contains('/') && access(p, X_OK) == 0) p else tool
}

/** JDK tool from ${'$'}JAVA_HOME/bin, else on PATH. */
private fun jdkTool(tool: String): String =
    getenv("JAVA_HOME")?.toKString()?.let { "$it/bin/$tool" }?.takeIf { access(it, X_OK) == 0 } ?: tool

/**
 * Compiles the Java runtime unless the cached classes in [dir] are newer than [src]. Returns [dir].
 * The cache lives next to the compiler build (shared by all outputs), so javac runs once per KxRt.java change.
 */
private fun runtimeClasses(src: String, dir: String): String {
    if (mtime("$dir/KxRt.class") >= mtime(src) && mtime(src) >= 0) return dir
    sh("mkdir -p '$dir' && '${jdkTool("javac")}' -nowarn --add-exports java.base/jdk.internal.misc=ALL-UNNAMED " +
        "-d '$dir' '$src'") {}
    return dir
}

/**
 * Links [ir] for the jvm target into the executable jar [output].
 * [runtime] is runtime.c; its directory holds jvm/KxRt.java and jvm/libc.c. [gcSrc] is the collector's gc.c,
 * [cflags] the preprocessor flags shared with the native link (-D..., -I...). [links] are extra C sources (FFI).
 */
internal fun linkJvm(ir: String, output: String, runtime: String, gcSrc: String, cflags: String, links: List<String>,
                     cc: String, opt: String, keepIr: Boolean) {
    val rtDir = runtime.substringBeforeLast('/', ".")
    val kxrt = "$rtDir/jvm/KxRt.java"
    if (access(kxrt, F_OK) != 0) throw CompileError("jvm runtime not found: $kxrt")
    for (l in links) if (!l.endsWith(".c")) throw CompileError("--link $l: the jvm target links C sources (.c) only")

    val tmp = "$output.jvm-tmp"
    val ll = "$output.ll"
    fun cleanup() { system("rm -rf '$tmp'"); if (!keepIr) remove(ll) }
    sh("rm -rf '$tmp' && mkdir -p '$tmp/classes'", ::cleanup)
    writeFile(ll, ir)

    // C sources -> bitcode. No vectorization (the translator handles scalars), no fortified libc calls, no stack
    // protector: the IR may then only call what KxRt.java provides.
    val cOpts = "-O2 -c -emit-llvm -fno-vectorize -fno-slp-vectorize -fno-stack-protector -fno-exceptions " +
        "-fno-unwind-tables -fno-asynchronous-unwind-tables -D_FORTIFY_SOURCE=0 -Wno-override-module $cflags"
    val inputs = arrayListOf("'$ll'")
    val sources = listOf(runtime to "", gcSrc to "", "$rtDir/jvm/libc.c" to " -fno-builtin") + links.map { it to "" }
    for ((i, s) in sources.withIndex()) {
        val bc = "$tmp/in$i.bc"
        sh("$cc $cOpts${s.second} '${s.first}' -o '$bc'", ::cleanup)
        inputs.add("'$bc'")
    }
    sh("'${sibling(opt, "llvm-link")}' ${inputs.joinToString(" ")} -o '$tmp/linked.bc'", ::cleanup)
    // Whole-program optimization: everything but main is internal. Vectorizers off (no vector types on the JVM).
    sh("'$opt' -passes='internalize,default<O2>' -internalize-public-api-list=main,exit -vectorize-loops=false " +
        "-vectorize-slp=false '$tmp/linked.bc' -S -o '$tmp/opt.ll'", ::cleanup)
    if (keepIr) sh("cp '$tmp/linked.bc' '$output.linked.bc' && cp '$tmp/opt.ll' '$output.opt.ll'", ::cleanup)

    val classBytes = try {
        JvmBackend(readFile("$tmp/opt.ll"), MAIN_CLASS, runtimeFunctions(readFile(kxrt))).generate()
    } catch (e: CompileError) { cleanup(); throw e }
    writeBytes("$tmp/classes/$MAIN_CLASS.class", classBytes)

    val rtClasses = runtimeClasses(kxrt, "$rtDir/../build/jvm-runtime")
    // jdk.internal.misc: Unsafe (memory); jdk.internal.util: Preconditions.checkIndex (bounds checks)
    writeFile("$tmp/manifest.txt", "Main-Class: $MAIN_CLASS\nAdd-Exports: java.base/jdk.internal.misc java.base/jdk.internal.util\n")
    sh("cp '$rtClasses'/*.class '$tmp/classes/' && '${jdkTool("jar")}' --create --file '$tmp/out.jar' " +
        "--manifest '$tmp/manifest.txt' -C '$tmp/classes' .", ::cleanup)
    writeFile("$tmp/launcher", "#!/bin/sh\nexec java $JVM_OPTS -jar \"\$0\" \"\$@\"\n")
    sh("cat '$tmp/launcher' '$tmp/out.jar' > '$output' && chmod +x '$output'", ::cleanup)
    cleanup()
}
