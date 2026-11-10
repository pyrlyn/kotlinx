package newkotlin

/**
 * `--target=wasm`: moves each loop nested in a loop of the program entry (`__main_argc_argv`, or `k.main` when opt
 * kept it) into a function of its own, after opt and before the LTO link.
 *
 * V8 starts every wasm function in its baseline compiler (Liftoff) and switches a function to optimized code
 * (TurboFan) only on a later call: there is no on-stack replacement for wasm, so a loop in a function that runs once
 * stays baseline code to the end (bench dispatch: its interface-call loop in `main` ran 4.9x slower than under
 * `node --no-liftoff`). An inner loop outlined from an outer one is called once per outer iteration, so V8 optimizes
 * it after a few calls; the outer loop that remains in `main` only calls it. [wasmKeepHotCalls] covers loops in
 * callees; this covers loops written in `main` itself. Loops of `main` without an inner loop are left alone (pulling
 * one out would still run it once). Engines that compile ahead of time (wasmtime) are not affected.
 *
 * LLVM does the extraction (`opt -passes=extract-blocks`, CodeExtractor): [wasmOutlineLoopBlocks] picks the blocks
 * from `opt -passes='instnamer,print<loops>'` output, [wasmMarkOutlined] makes the new functions `noinline` so the
 * LTO link does not put them back.
 */
internal val WASM_RUN_ONCE = listOf("__main_argc_argv", "k.main")

/**
 * Lines of an `-extract-blocks-file` (`<function> <block>;<block>...`, one new function per line) for every
 * depth-2 loop of a [WASM_RUN_ONCE] function in [loopInfo], the lines `opt -passes='print<loops>'` prints
 * ("Loop info for function 'f':", "Loop at depth 2 containing: %a<header>,%b<latch><exiting>").
 */
internal fun wasmOutlineLoopBlocks(loopInfo: List<String>): List<String> {
    val out = ArrayList<String>()
    var fn: String? = null
    for (raw in loopInfo) {
        val l = raw.trim()
        if (l.startsWith("Loop info for function '")) { fn = l.removePrefix("Loop info for function '").substringBeforeLast("'"); continue }
        if (fn !in WASM_RUN_ONCE || !l.startsWith("Loop at depth 2 containing: ")) continue
        val blocks = l.removePrefix("Loop at depth 2 containing: ").split(',').map { b ->
            b.trim().removePrefix("%").substringBefore('<').removeSurrounding("\"")
        }
        // instnamer names every block; a numbered one would not be found by name.
        if (blocks.isEmpty() || blocks.any { it.isEmpty() || it.all(Char::isDigit) || ' ' in it || ';' in it }) continue
        out.add("$fn ${blocks.joinToString(";")}")
    }
    return out
}

/** Adds `noinline` to the functions of [ir] extracted from a [WASM_RUN_ONCE] function ([before]: names defined
 * before the extraction); returns null when there is none. */
internal fun wasmMarkOutlined(ir: String, before: Set<String>): String? {
    val define = Regex("""^(define [^@]*@("[^"]+"|[-\w.$]+)\(.*?)\s*\{$""")
    val groups = Regex("""^attributes #(\d+) = """, RegexOption.MULTILINE).findAll(ir).map { it.groupValues[1].toInt() }
    val g = (groups.maxOrNull() ?: 0) + 1
    var n = 0
    val text = ir.split('\n').joinToString("\n") { l ->
        val m = define.find(l) ?: return@joinToString l
        val name = m.groupValues[2].removeSurrounding("\"")
        if (name in before || WASM_RUN_ONCE.none { name.startsWith("$it.") }) l else { n++; "${m.groupValues[1]} #$g {" }
    }
    return if (n == 0) null else "$text\nattributes #$g = { noinline }\n"
}

/** Names of the functions [ir] defines. */
internal fun definedFunctions(ir: String): Set<String> =
    Regex("""^define [^@]*@("[^"]+"|[-\w.$]+)\(""", RegexOption.MULTILINE).findAll(ir).map { it.groupValues[1].removeSurrounding("\"") }.toSet()
