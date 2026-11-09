package newkotlin

/**
 * `--target=wasm`: keeps calls in the loops of `main` from being inlined into it, when the callee (or anything it
 * calls) has a loop.
 *
 * V8 starts every wasm function in its baseline compiler (Liftoff) and switches to optimized code (TurboFan) only on a
 * later call: it has no on-stack replacement for wasm. `main` runs once, so a hot loop LLVM inlines into it runs as
 * baseline code until the end (nbody: `advance` inlined into `main`'s loop ran 2.6x slower than the same function
 * called from it). A call kept in the loop is entered again and again and gets optimized after a few calls. Code in
 * `main`'s own loops is not affected (it stays baseline code either way); wasmtime and other engines that compile
 * ahead of time are not affected.
 *
 * Works on the IR text [NativeBackend] emits, before `opt`: a loop is a branch back to a label defined earlier in
 * the same function (blocks are emitted in source order), and a call inside one in `@k.main` to a function defined in
 * the module that has a loop, directly or through its own direct calls, gets the `noinline` call-site attribute.
 */
internal fun wasmKeepHotCalls(ir: String, entry: String = "k.main"): String {
    val lines = ir.split('\n').toMutableList()
    val defineName = Regex("""^define [^@]*@("[^"]+"|[-\w.$]+)\(""")
    val labelDef = Regex("""^("[^"]+"|[-\w.$]+):""")
    val labelUse = Regex("""label %("[^"]+"|[-\w.$]+)""")
    val callee = Regex("""\bcall [^@]*@("[^"]+"|[-\w.$]+)\(""")

    class Fn(val start: Int, val end: Int) {
        val loops = ArrayList<IntRange>() // line ranges from a loop's header label to its back edge
        val calls = HashSet<String>()
    }
    val fns = HashMap<String, Fn>()
    var i = 0
    while (i < lines.size) {
        val m = defineName.find(lines[i])
        if (m == null) { i++; continue }
        var j = i + 1
        while (j < lines.size && lines[j] != "}") j++
        val f = Fn(i, j)
        val labels = HashMap<String, Int>()
        for (k in i + 1 until j) {
            val l = lines[k]
            labelDef.find(l)?.let { labels[it.groupValues[1]] = k }
            if (l.trimStart().startsWith("br ")) for (u in labelUse.findAll(l)) labels[u.groupValues[1]]?.let { f.loops.add(it..k) }
            callee.find(l)?.let { f.calls.add(it.groupValues[1]) }
        }
        fns[m.groupValues[1]] = f
        i = j + 1
    }
    val main = fns[entry] ?: return ir
    // A function is hot-loop code if it has a loop or calls (directly) one that does.
    val hot = HashSet<String>()
    fns.forEach { (n, f) -> if (f.loops.isNotEmpty()) hot.add(n) }
    var changed = true
    while (changed) {
        changed = false
        fns.forEach { (n, f) -> if (n !in hot && f.calls.any { it in hot }) { hot.add(n); changed = true } }
    }
    var marked = 0
    for (k in main.start + 1 until main.end) {
        if (main.loops.none { k in it }) continue
        val l = lines[k]
        val m = callee.find(l) ?: continue
        val name = m.groupValues[1]
        if (name == entry || name !in hot) continue
        // the argument list ends at the parenthesis matching the one after the callee
        var depth = 0; var p = m.range.last; var close = -1
        while (p < l.length) {
            when (l[p]) { '(' -> depth++; ')' -> { depth--; if (depth == 0) { close = p; break } } }
            p++
        }
        if (close < 0 || l.substring(close + 1).trimStart().startsWith("noinline")) continue
        lines[k] = l.substring(0, close + 1) + " noinline" + l.substring(close + 1)
        marked++
    }
    return if (marked == 0) ir else lines.joinToString("\n")
}
