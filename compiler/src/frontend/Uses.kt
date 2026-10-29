package newkotlin

/**
 * Syntactic name-use analysis for Perceus-style reference counting (backend-independent).
 * A use is a NameE occurrence. `movable` uses sit directly in an ownership-taking position
 * (constructor/function argument, VarS initializer, assigned value, return value) and are not
 * under a branch, loop or lambda, i.e. they are evaluated exactly once whenever the statement runs.
 */
class Use(val node: NameE, val movable: Boolean)

object Uses {
    fun ofStmt(s: S): List<Use> { val out = ArrayList<Use>(); stmt(s, true, out); return out }

    private fun stmt(s: S, straight: Boolean, out: MutableList<Use>) {
        when (s) {
            is VarS -> expr(s.init, straight, straight, out)
            is AssignS -> { expr(s.target, straight, false, out); expr(s.value, straight, straight, out) }
            is IncS -> expr(s.target, straight, false, out)
            is WhileS -> { expr(s.cond, false, false, out); block(s.body, out) }
            is ForS -> { expr(s.iter, straight, false, out); block(s.body, out) }
            is RetS -> s.e?.let { expr(it, straight, straight, out) }
            is ExprS -> expr(s.e, straight, false, out)
            is JumpS -> {}
        }
    }

    private fun block(b: Block, out: MutableList<Use>) { for (s in b.stmts) stmt(s, false, out) }

    /** `pos` = this expression is in an ownership-taking position. */
    private fun expr(e: E, straight: Boolean, pos: Boolean, out: MutableList<Use>) {
        when (e) {
            is NameE -> out.add(Use(e, straight && pos))
            is PostIncE -> expr(e.target, straight, false, out)
            is StrL -> for (p in e.parts) if (p is E) expr(p, straight, false, out)
            is CallE -> {
                expr(e.callee, straight, false, out)
                for (a in e.args) expr(a, straight, true, out)
                e.lambda?.let { block(it, out) }
            }
            is MemberE -> expr(e.recv, straight && !e.safe, false, out)
            is IndexE -> { expr(e.recv, straight, false, out); expr(e.idx, straight, false, out) }
            is BinE -> {
                expr(e.l, straight, false, out)
                expr(e.r, straight && e.op != "&&" && e.op != "||" && e.op != "?:", false, out)
            }
            is UnE -> expr(e.e, straight, false, out)
            is NamedE -> expr(e.e, straight, pos, out)
            is IsE -> expr(e.e, straight, false, out)
            is AsE -> expr(e.e, straight, pos, out)
            is IfE -> { expr(e.cond, straight, false, out); block(e.th, out); e.el?.let { block(it, out) } }
            is LambdaE -> block(e.body, out)
            is TryE -> { block(e.body, out); e.catches.forEach { block(it.body, out) }; e.fin?.let { block(it, out) } }
            is WhenE -> {
                e.subj?.let { expr(it, straight, false, out) }
                e.branches.forEachIndexed { i, (conds, b) ->
                    conds?.forEach { expr(it, straight && i == 0, false, out) }
                    block(b, out)
                }
            }
            else -> {}
        }
    }
}

/** Escape analysis helpers (syntactic, conservative). */
object Escape {
    /**
     * Visits every occurrence of `name` in the statements, with its parent and grand-parent expression.
     * Returns false if `ok` rejects any occurrence (or the name is redeclared, i.e. shadowed).
     */
    fun allUses(stmts: List<S>, name: String, ok: (parent: E?, grand: E?) -> Boolean): Boolean {
        val w = Walker(name, ok)
        stmts.forEach { w.s(it) }
        return w.good
    }

    private class Walker(val name: String, val ok: (E?, E?) -> Boolean) {
        var good = true
        fun e(x: E, p: E?, g: E?) {
            if (!good) return
            when (x) {
                is NameE -> if (x.n == name && !ok(p, g)) good = false
                is PostIncE -> e(x.target, x, p)
                is StrL -> for (q in x.parts) if (q is E) e(q, x, p)
                is CallE -> { e(x.callee, x, p); x.args.forEach { e(it, x, p) }; x.lambda?.let { b(it) } }
                is MemberE -> e(x.recv, x, p)
                is IndexE -> { e(x.recv, x, p); e(x.idx, x, p) }
                is BinE -> { e(x.l, x, p); e(x.r, x, p) }
                is UnE -> e(x.e, x, p)
                is NamedE -> e(x.e, x, p)
                is IsE -> e(x.e, x, p)
                is AsE -> e(x.e, x, p)
                is IfE -> { e(x.cond, x, p); b(x.th); x.el?.let { b(it) } }
                is WhenE -> { x.subj?.let { e(it, x, p) }; x.branches.forEach { (c, bl) -> c?.forEach { e(it, x, p) }; b(bl) } }
                is LambdaE -> if (containsName(x.body)) good = false // captured by a closure: escapes
                is TryE -> { b(x.body); x.catches.forEach { if (it.name == name) good = false; b(it.body) }; x.fin?.let { b(it) } }
                else -> {}
            }
        }
        fun s(x: S) {
            when (x) {
                is VarS -> { if (x.name == name) good = false; e(x.init, null, null) }
                is AssignS -> { e(x.target, null, null); e(x.value, null, null) }
                is IncS -> e(x.target, null, null)
                is WhileS -> { e(x.cond, null, null); b(x.body) }
                is ForS -> { if (x.name == name) good = false; e(x.iter, null, null); b(x.body) }
                is RetS -> x.e?.let { e(it, null, null) }
                is ExprS -> e(x.e, null, null)
                is JumpS -> {}
            }
        }
        fun containsName(bl: Block): Boolean { var hit = false; val w = Walker(name) { _, _ -> hit = true; true }; bl.stmts.forEach { w.s(it) }; return hit }
        fun b(bl: Block) { if (name in bl.params) good = false; bl.stmts.forEach { s(it) } }
    }

    /** True if a method body uses `this` only as the receiver of field reads/writes and calls no sibling method implicitly. */
    fun methodKeepsThis(f: FunD, fields: Set<String>, methods: Set<String>): Boolean {
        val body = f.body?.stmts ?: listOf(RetS(f.exprBody))
        if (f.params.any { it.name in fields }) return false
        return allUses(body, "this") { p, _ -> p is MemberE && p.n in fields } &&
            methods.all { m -> allUses(body, m) { p, _ -> p !is CallE } }
    }
}
