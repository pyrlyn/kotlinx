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
            is IfE -> { expr(e.cond, straight, false, out); block(e.th, out); e.el?.let { block(it, out) } }
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
