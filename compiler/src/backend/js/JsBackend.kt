@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package newkotlin

import kotlin.io.encoding.Base64

/**
 * Js target, last step: the linked and optimized LLVM IR of the whole program (program + runtime.c + GC + js/libc.c)
 * becomes one JavaScript file: the parts of compiler/runtime/js/runtime.js the code reaches, the translated
 * functions, then the start call that loads the data segment and runs the constructors and main, all inside one
 * function (its `var`s are closure variables, cheaper for V8 than script-level `let`). The driver produces the IR
 * (driver/JsDriver.kt). `compact = false` keeps LLVM function names, line breaks and runtime.js comments.
 */
object JsBackend {
    fun translate(ir: String, runtimeJs: String, compact: Boolean = true, warn: (String) -> Unit): String {
        val m = LlParser(ir).parse()
        val runtimeNames = Regex("""\$[\w$]*""").findAll(runtimeJs).map { it.value }.toSet()
        val gen = JsGen(m, warn, runtimeNames, compact)
        val code = gen.generate()
        val defined = Regex("""(?m)^(?:function|var|class) (\$[\w$]*)""").findAll(runtimeJs).map { it.groupValues[1] }.toSet()
        val missing = gen.externs.filter { it !in defined }
        if (missing.isNotEmpty()) throw CompileError("JS backend: runtime.js does not define ${missing.joinToString(", ")}")
        val main = m.funcs["main"]?.takeIf { !it.isDecl } ?: throw CompileError("JS backend: no main function")
        val rt = usedRuntime(if (compact) compactRuntime(runtimeJs) else runtimeJs, code)
        val out = StringBuilder(rt.length + code.length + 1024)
        out.append("(function(){\n").append(rt).append('\n').append(code)
        out.append("_start(\"").append(Base64.encode(gen.dataBytes())).append("\",").append(gen.dataStart).append(',')
            .append(gen.dataEnd).append(",[").append(gen.ctors.joinToString(",")).append("],").append(gen.jsName(main.name)).append(");\n")
        out.append("})();\n")
        return out.toString()
    }

    /** runtime.js without comment lines, indentation and blank lines (it has no multi-line strings). */
    fun compactRuntime(js: String): String =
        js.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") }.joinToString("\n")

    private val decl = Regex("""^(function|var|class) ([A-Za-z_$][\w$]*)""")
    private val ident = Regex("""[A-Za-z_$][\w$]*""")

    /**
     * runtime.js without the functions code (and _start) never reaches. Each top-level declaration starts at
     * column 0 and runs to the next one; var/class declarations (state, small) are always kept.
     */
    fun usedRuntime(runtimeJs: String, code: String): String {
        val lines = runtimeJs.lines()
        val starts = lines.indices.filter { decl.containsMatchIn(lines[it]) }
        if (starts.isEmpty()) return runtimeJs
        val head = lines.subList(0, starts[0]).joinToString("\n")
        val chunks = starts.mapIndexed { k, a -> lines.subList(a, if (k + 1 < starts.size) starts[k + 1] else lines.size).joinToString("\n") }
        val fnChunk = HashMap<String, Int>()
        chunks.forEachIndexed { k, c -> decl.find(c)!!.groupValues.let { if (it[1] == "function") fnChunk[it[2]] = k } }
        val keep = BooleanArray(chunks.size); val work = ArrayList<String>()
        fun scan(text: String) { for (m in ident.findAll(text)) fnChunk[m.value]?.let { if (!keep[it]) { keep[it] = true; work.add(chunks[it]) } } }
        chunks.forEachIndexed { k, c -> if (k !in fnChunk.values) { keep[k] = true; scan(c) } }
        scan(code); scan("_start")
        while (work.isNotEmpty()) scan(work.removeAt(work.size - 1))
        return head + "\n" + chunks.filterIndexed { k, _ -> keep[k] }.joinToString("\n")
    }
}
