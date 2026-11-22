package newkotlin.mutation

import newkotlin.compileToIr
import newkotlin.deferredRc
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * The golden feature suite of compiler/test.sh (compiler/tests/<name>.kt -> <name>.out), run on the JVM build:
 * compileToIr in-process, then clang (no opt, -O1, system malloc, --gc=perceus) and the program's output must
 * equal <name>.out. When the IR is byte-identical to the IR of the unmutated compiler (build/ir-baseline.txt,
 * written by `-Dkx.writeBaseline=true test` after every test passed through clang), the program is the same
 * one that already passed, so clang is skipped. That keeps a mutation run in minutes, not hours.
 */
class GoldenTest {
    companion object {
        val root = File(System.getProperty("kx.root") ?: "../..")
        private val baselineFile = File(System.getProperty("kx.baseline") ?: "build/ir-baseline.txt")
        private val writing = System.getProperty("kx.writeBaseline") == "true"
        private val baseline: MutableMap<String, String> =
            if (baselineFile.exists()) baselineFile.readLines().filter { ' ' in it }.associate { it.substringBefore(' ') to it.substringAfter(' ') }.toMutableMap()
            else mutableMapOf()
        private val work = File(System.getenv("KX_MUT_TMP") ?: (System.getProperty("java.io.tmpdir") + "/kx-golden")).also { it.mkdirs() }

        @JvmStatic fun cases(): List<String> =
            File(root, "compiler/tests").listFiles { f -> f.name.endsWith(".kt") }!!.map { it.name.removeSuffix(".kt") }.sorted()
                .filter { it !in (System.getProperty("kx.skip") ?: "").split(",") }

        /**
         * C compiler for the golden programs: ${'$'}NK_CC, else the first `clang` on PATH, else /usr/bin/clang (Apple clang).
         * Null when none is executable: the tests are then skipped (not failed) with a message naming NK_CC.
         */
        val cc: String? by lazy {
            System.getenv("NK_CC")?.takeIf { it.isNotEmpty() }
                ?: (System.getenv("PATH") ?: "").split(File.pathSeparator).filter { it.isNotEmpty() }
                    .map { File(it, "clang") }.firstOrNull { it.canExecute() }?.path
                ?: File("/usr/bin/clang").takeIf { it.canExecute() }?.path
        }

        private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        @Synchronized private fun record(name: String, h: String) {
            baseline[name] = h
            baselineFile.parentFile.mkdirs()
            baselineFile.writeText(baseline.entries.sortedBy { it.key }.joinToString("") { "${it.key} ${it.value}\n" })
        }
    }

    @Test
    fun compilationOrderDoesNotAffectIr() {
        // Different class/extern/bound/receiver names at the same source lines exposed process-wide caches.
        val sources = listOf(
            "fun apply(f: (Int) -> Int): Int = f(2)\nfun main() { println(apply { it + 1 }) }",
            "class Other(val x: Int)\nfun main() { val f = { x: Int -> Other(x).x }; println(f(4)) }",
            "fun <T> id(x: T): T = x as T\nfun main() { println(id(3)) }"
        )
        val expected = sources.map { compileToIr(it) }
        for (i in sources.indices.reversed()) assertEquals(expected[i], compileToIr(sources[i]), "IR depends on compilation order: $i")
        // A failed compilation must also drop its partially populated context.
        try { compileToIr("fun main() { missing() }") } catch (e: newkotlin.CompileError) { }
        for (i in sources.indices) assertEquals(expected[i], compileToIr(sources[i]), "IR changed after failure: $i")
    }

    private fun runNative(name: String, ir: String): String {
        val dir = File(work, "${ProcessHandle.current().pid()}-${Thread.currentThread().id}").also { it.mkdirs() }
        val ll = File(dir, "$name.ll").also { it.writeText(ir) }
        val exe = File(dir, name)
        val cc = cc ?: error("no C compiler") // guarded by assumeTrue in golden()
        val build = ProcessBuilder(cc, "-O1", "-DRT_PLAIN_MALLOC", "-Wno-override-module", "-w", "-I", "$root/native/gc/c",
            ll.path, "$root/compiler/runtime/runtime.c", "$root/native/gc/c/perceus/gc.c", "-o", exe.path)
            .redirectErrorStream(true).start()
        val bout = build.inputStream.bufferedReader().readText()
        if (!build.waitFor(120, TimeUnit.SECONDS) || build.exitValue() != 0) fail<Unit>("$name: clang failed: ${bout.take(2000)}")
        val p = ProcessBuilder(exe.path).directory(root).redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = Thread { out.append(p.inputStream.bufferedReader().readText()) }.also { it.start() }
        if (!p.waitFor(10, TimeUnit.SECONDS)) { p.destroyForcibly(); fail<Unit>("$name: program timed out") }
        reader.join(5000)
        return out.toString().trimEnd('\n')
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun golden(name: String) {
        deferredRc = false
        val src = File(root, "compiler/tests/$name.kt").readText()
        val expected = File(root, "compiler/tests/$name.out").readText().trimEnd('\n')
        val ir = compileToIr(src)
        val h = sha(ir)
        if (!writing && baseline[name] == h) return
        assumeTrue(cc != null) { "no C compiler: set NK_CC or put clang on PATH (golden program $name not run)" }
        val got = runNative(name, ir)
        assertEquals(expected, got, "golden output of compiler/tests/$name.kt")
        if (writing) record(name, h)
    }
}
