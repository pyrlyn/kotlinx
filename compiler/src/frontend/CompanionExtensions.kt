package newkotlin

// Token-level rewrite of companion extensions run before parsing (split out of Parser.kt).

/**
 * Companion extensions (`companion fun C.f() = ...`, `companion val C.x = ...`, `+CompanionExtensions`) on a top-level
 * class C of this file: the declaration (without `companion` and `C.`) is moved into C's companion object, which is
 * created when C has none. Declarations this rewrite does not recognize are left in place (and refused by the parser).
 */
internal fun companionExtensions(ts: List<Tok>): List<Tok> {
    if (ts.none { isIdTok(it, "companion") }) return ts
    fun nextNonNl(i: Int): Int { var j = i; while (j < ts.size && ts[j].k == K.NL) j++; return j }
    val moved = LinkedHashMap<String, ArrayList<Tok>>()
    val drop = HashSet<Int>()
    var depth = 0; var i = 0
    while (i < ts.size && ts[i].k != K.EOF) {
        val x = ts[i]
        if (isOpTok(x, "(", "[", "{")) depth++
        if (isOpTok(x, ")", "]", "}")) depth--
        val lineStart = i == 0 || ts[i - 1].k == K.NL
        if (depth == 0 && lineStart && isIdTok(x, "companion") && i + 1 < ts.size && ts[i + 1].k == K.ID && ts[i + 1].s != "object") {
            var j = i + 1
            val mods = ArrayList<Tok>()
            while (j < ts.size && ts[j].k == K.ID && !isIdTok(ts[j], "fun", "val", "var")) { mods.add(ts[j]); j++ }
            if (j + 3 < ts.size && isIdTok(ts[j], "fun", "val", "var") && ts[j + 1].k == K.ID && isOpTok(ts[j + 2], ".") && ts[j + 3].k == K.ID) {
                val cls = ts[j + 1].s
                // The declaration ends at a newline outside brackets that does not continue it.
                var k = j + 3; var d = 0
                while (k < ts.size && ts[k].k != K.EOF) {
                    val y = ts[k]
                    if (isOpTok(y, "(", "[", "{")) d++
                    if (isOpTok(y, ")", "]", "}")) d--
                    if (y.k == K.NL && d == 0) {
                        val prev = ts[k - 1]; val n = ts[nextNonNl(k)]
                        val cont = (prev.k == K.OP && prev.s !in setOf(")", "]", "}")) || isOpTok(n, "{", "=", ".", "?.", ":") || isIdTok(n, "get", "set", "private", "public", "internal", "protected")
                        if (!cont) break
                    }
                    k++
                }
                val decl = ArrayList<Tok>()
                decl.addAll(mods); decl.add(ts[j]); decl.addAll(ts.subList(j + 3, k)); decl.add(Tok(K.NL, "\n", ts[k - 1].line))
                moved.getOrPut(cls) { ArrayList() }.addAll(decl)
                for (q in i until k) drop.add(q)
                i = k; continue
            }
        }
        i++
    }
    if (moved.isEmpty()) return ts
    val out = ArrayList<Tok>()
    val inserts = HashMap<Int, List<Tok>>()   // token index -> tokens inserted before it
    depth = 0; i = 0
    val done = HashSet<String>()
    while (i < ts.size) {
        val x = ts[i]
        if (isOpTok(x, "(", "[", "{")) depth++
        if (isOpTok(x, ")", "]", "}")) depth--
        if (depth == 0 && i !in drop && isIdTok(x, "class") && i + 1 < ts.size && ts[i + 1].k == K.ID && ts[i + 1].s in moved && ts[i + 1].s !in done) {
            val cls = ts[i + 1].s; val decls = moved.getValue(cls)
            // Header: up to the body's `{` (outside parentheses) or the end of the line.
            var k = i + 2; var pd = 0; var body = -1
            while (k < ts.size && ts[k].k != K.EOF) {
                val y = ts[k]
                if (isOpTok(y, "(", "<")) pd++
                if (isOpTok(y, ")", ">")) pd--
                if (pd == 0 && isOpTok(y, "{")) { body = k; break }
                if (pd == 0 && y.k == K.NL) { val n = nextNonNl(k); if (isOpTok(ts[n], "{")) { body = n; break }; if (!isOpTok(ts[n], ":", ",")) break }
                k++
            }
            val ln = x.line
            fun tk(kind: K, v: String) = Tok(kind, v, ln)
            if (body < 0) {
                inserts[k] = listOf(tk(K.OP, "{"), tk(K.NL, "\n"), tk(K.ID, "companion"), tk(K.ID, "object"), tk(K.OP, "{"), tk(K.NL, "\n")) + decls + listOf(tk(K.OP, "}"), tk(K.NL, "\n"), tk(K.OP, "}"))
            } else {
                // Inside the body: an existing companion object/block, else a new one before the closing brace.
                var b = body + 1; var bd = 1; var compOpen = -1
                while (b < ts.size && ts[b].k != K.EOF) {
                    val y = ts[b]
                    if (bd == 1 && isIdTok(y, "companion")) {
                        var c = b + 1
                        if (isIdTok(ts[c], "object")) c++
                        if (ts[c].k == K.ID) c++
                        c = nextNonNl(c)
                        if (isOpTok(ts[c], "{") && compOpen < 0) compOpen = c
                    }
                    if (isOpTok(y, "(", "[", "{")) bd++
                    if (isOpTok(y, ")", "]", "}")) { bd--; if (bd == 0) break }
                    b++
                }
                if (bd != 0) { i++; continue }
                if (compOpen >= 0) inserts[compOpen + 1] = listOf(tk(K.NL, "\n")) + decls
                else inserts[b] = listOf(tk(K.NL, "\n"), tk(K.ID, "companion"), tk(K.ID, "object"), tk(K.OP, "{"), tk(K.NL, "\n")) + decls + listOf(tk(K.OP, "}"), tk(K.NL, "\n"))
            }
            done.add(cls)
        }
        i++
    }
    // Extensions on classes not found here stay as they were (the parser refuses them).
    val keep = moved.keys - done
    for (q in ts.indices) {
        inserts[q]?.let { out.addAll(it) }
        if (q in drop && !keepIdx(q, ts, keep)) continue
        out.add(ts[q])
    }
    return out
}

/** Whether dropped token [q] belongs to an extension of a class in [keep] (whose declaration is restored). */
internal fun keepIdx(q: Int, ts: List<Tok>, keep: Set<String>): Boolean {
    if (keep.isEmpty()) return false
    // Walk back to the declaration's `companion` token and read its receiver class.
    var i = q
    while (i > 0 && !(isIdTok(ts[i], "companion") && (i == 0 || ts[i - 1].k == K.NL))) i--
    var j = i + 1
    while (j < ts.size && ts[j].k == K.ID && !isIdTok(ts[j], "fun", "val", "var")) j++
    return j + 1 < ts.size && ts[j + 1].s in keep
}

const val COLL_LIT = "\$collLit"

/** Recursive-descent parser from tokens to a `Program`. */
