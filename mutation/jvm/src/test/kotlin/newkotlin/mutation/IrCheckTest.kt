package newkotlin.mutation

import newkotlin.Lexer
import newkotlin.Parser
import newkotlin.compileGenerics
import newkotlin.deferredRc
import newkotlin.withAny
import newkotlin.withPrelude
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File

/**
 * The IR checks of compiler/test.sh (compiler/tests/ir/<name>.kt) on the JVM build: `// IR <fn>: <regex>` must match
 * a line of function @k.<fn> in the generated IR, `// IR-NOT <fn>: <regex>` must match none.
 */
class IrCheckTest {
    companion object {
        @JvmStatic fun cases(): List<String> =
            (File(GoldenTest.root, "compiler/tests/ir").listFiles { f -> f.name.endsWith(".kt") } ?: emptyArray()).map { it.name.removeSuffix(".kt") }.sorted()
        private val directive = Regex("""^// (IR|IR-NOT) ([A-Za-z0-9_]+): (.*)$""")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun ir(name: String) {
        deferredRc = false
        val src = File(GoldenTest.root, "compiler/tests/ir/$name.kt").readText()
        val ir = compileGenerics(withAny(src, withPrelude(src, Parser(Lexer(src).lex()).program()))).lines()
        for (m in src.lines().mapNotNull { directive.matchEntire(it) }) {
            val (kind, fn, re) = m.destructured
            val start = ir.indexOfFirst { it.startsWith("define ") && "@k.$fn(" in it }
            assertTrue(start >= 0, "$name: no function @k.$fn")
            val body = ir.drop(start + 1).takeWhile { !it.startsWith("}") }
            val hit = body.any { Regex(re).containsMatchIn(it) }
            if (kind == "IR") assertTrue(hit, "$name: $fn: no match for /$re/") else assertTrue(!hit, "$name: $fn: unexpected /$re/")
        }
    }
}
