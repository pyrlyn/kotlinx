package newkotlin.jvm

/*
 * Loop outlining for the jvm target: every loop nested in another loop moves into a method of its own.
 *
 * HotSpot compiles a long-running loop nest of a method that is invoked rarely (main, or any function that runs one
 * big loop nest) through on-stack replacement, entered at the header of the loop whose back-edge counter overflowed:
 * usually the innermost one. C2 then sees the enclosing loops entered in the middle and does not optimize them as
 * counted loops; such code ran about 2.5x slower in steady state than the same loop nest compiled as a normal method.
 * With each inner loop in its own method, that method is invoked once per iteration of the enclosing loop, compiles
 * normally, and C2 inlines it back with its loops intact.
 *
 * Rewrite of a loop L (header H) of function F into a new function M:
 *   M: an entry block that jumps to H (H's phis take their initial values from M's parameters or constants), the
 *      loop's blocks, and one block per exit edge that hands the live-out values to F and returns the exit's index.
 *      Exits into dead ends (blocks that can only end in `unreachable`, such as failed bounds checks) are copied
 *      into M instead, so they cost F nothing.
 *   F: H's outside predecessors jump to a block that calls M with the loop's live-in values, defines each live-out
 *      under its original SSA name, and switches on M's result to one block per exit edge, which jumps to the exit's
 *      original target. The rest of F is unchanged.
 * Live-outs are returned as M's result when there is one exit and one value, else through static fields of the
 * class (`kx.put` / `kx.get` pseudo-instructions); the jvm target runs program code on one thread.
 */

/** Terminators the CFG analysis understands; functions with any other terminator are left alone. */
private val TERMS = setOf("br", "switch", "ret", "unreachable")

/** Calls that depend on the frame of the function they are in. */
private val FRAME_CALLS = listOf("llvm.stacksave", "llvm.stackrestore", "llvm.va_", "llvm.frameaddress", "llvm.returnaddress")

/** Largest dead-end region (blocks) copied into an outlined function. */
private const val MAX_DEAD = 32

/** JVM parameter slots of an outlined function (the limit is 255). */
private const val MAX_PARAM_SLOTS = 200

internal class LoopOutliner(private val m: LModule) {
    /** Static fields that carry live-out values from outlined loops to their callers: name -> type. */
    val fields = LinkedHashMap<String, LType>()
    private var seq = 0

    fun run() {
        for (f in m.funcs.values.toList()) {
            if (f.isDecl || f.varargs || f.blocks.any { it.insts.lastOrNull()?.op !in TERMS }) continue
            removeUnreachable(f)
            val tried = HashSet<String>()
            while (true) {
                val g = Graph(f)
                val l = g.loops().filter { it.depth >= 2 && it.header !in tried }.maxByOrNull { it.depth } ?: break
                if (!outline(f, g, l)) tried.add(l.header)
            }
        }
    }

    // ------------------------------------------------------------ analysis

    private class Loop(val header: String, val blocks: Set<String>, val depth: Int)

    private class Graph(f: LFunc) {
        val block = f.blocks.associateBy { it.name }
        val succ = f.blocks.associate { it.name to succs(it) }
        val pred = HashMap<String, MutableList<String>>()
        val rpo: List<String>
        private val index = HashMap<String, Int>()
        private val idom: IntArray

        init {
            for (b in f.blocks) pred.getOrPut(b.name) { ArrayList() }
            for ((b, ss) in succ) for (s in ss) pred.getOrPut(s) { ArrayList() }.add(b)
            rpo = reversePostorder(f.blocks[0].name, succ)
            rpo.forEachIndexed { k, n -> index[n] = k }
            // Cooper, Harvey, Kennedy: "A Simple, Fast Dominance Algorithm"
            idom = IntArray(rpo.size) { -1 }; idom[0] = 0
            var changed = true
            while (changed) {
                changed = false
                for (k in 1 until rpo.size) {
                    var d = -1
                    for (p in pred[rpo[k]]!!) {
                        val pi = index[p] ?: continue
                        if (idom[pi] < 0) continue
                        d = if (d < 0) pi else intersect(pi, d)
                    }
                    if (idom[k] != d) { idom[k] = d; changed = true }
                }
            }
        }

        private fun intersect(a: Int, b: Int): Int {
            var x = a; var y = b
            while (x != y) { while (x > y) x = idom[x]; while (y > x) y = idom[y] }
            return x
        }

        fun dominates(a: String, b: String): Boolean {
            val x = index[a] ?: return false
            var y = index[b] ?: return false
            while (y > x) y = idom[y]
            return y == x
        }

        /** Natural loops (back edges to the same header merged), with their nesting depth. */
        fun loops(): List<Loop> {
            val bodies = LinkedHashMap<String, HashSet<String>>()
            for (b in rpo) for (h in succ[b]!!) {
                if (!dominates(h, b)) continue
                val body = bodies.getOrPut(h) { hashSetOf(h) }
                val work = ArrayDeque<String>()
                if (body.add(b)) work.add(b)
                while (work.isNotEmpty()) for (p in pred[work.removeLast()]!!) if (p in index && body.add(p)) work.add(p)
            }
            return bodies.map { (h, body) -> Loop(h, body, 1 + bodies.count { (h2, b2) -> h2 != h && h in b2 }) }
        }
    }

    // ------------------------------------------------------------ the rewrite

    /** Moves loop [l] of [f] into a new function. Returns false (and changes nothing) when it cannot. */
    private fun outline(f: LFunc, g: Graph, l: Loop): Boolean {
        val inL = l.blocks
        val h = l.header
        val order = f.blocks.map { it.name }
        if (inL.any { !movable(g.block[it]!!) }) return false

        // exits: dead-end targets are copied into M, the others are exit edges
        val exits = ArrayList<Pair<String, String>>()             // (block in L, target outside)
        val dead = LinkedHashSet<String>()
        val deadCache = HashMap<String, Set<String>?>()
        for (b in order) if (b in inL) for (s in g.succ[b]!!) {
            if (s in inL) continue
            val region = deadCache.getOrPut(s) { deadRegion(g, s, inL) }
            if (region != null) dead.addAll(region) else exits.add(b to s)
        }

        // types and definition blocks of F's values
        val typeOf = HashMap<String, LType>()
        f.params.forEachIndexed { k, t -> typeOf[f.paramNames[k]] = t }
        val defBlock = HashMap<String, String>()
        for (b in f.blocks) for (i in b.insts) i.res?.let { typeOf[it] = i.ty; defBlock[it] = b.name }
        val defL = HashSet<String>()
        for (b in inL) for (i in g.block[b]!!.insts) i.res?.let { defL.add(it) }

        // where F (outside L) uses values of L: non-phi uses at their block, phi uses at the incoming block
        val usesAt = HashMap<String, HashSet<String>>()
        fun use(at: String, v: LValue) { if (v is Local && v.name in defL) usesAt.getOrPut(at) { HashSet() }.add(v.name) }
        for (b in f.blocks) {
            if (b.name in inL) continue
            for (i in b.insts) {
                if (i.op == "phi") { for ((v, p) in i.incoming) if (p !in inL) use(p, v); continue }
                i.ops.forEach { use(b.name, it.v) }; i.callee?.let { use(b.name, it) }
            }
        }

        // live-outs needed after each exit: used on a path from its target that does not re-enter L, or by the
        // target's phis on the exit edge itself
        val needed = exits.map { (b, e) ->
            val vs = LinkedHashSet<String>()
            for (phi in g.block[e]!!.insts) {
                if (phi.op != "phi") break
                for ((v, p) in phi.incoming) if (p == b && v is Local && v.name in defL) vs.add(v.name)
            }
            val seen = hashSetOf(e); val work = ArrayDeque(listOf(e))
            while (work.isNotEmpty()) {
                val x = work.removeLast()
                usesAt[x]?.let { vs.addAll(it) }
                for (y in g.succ[x]!!) if (y !in inL && seen.add(y)) work.add(y)
            }
            if (vs.any { !g.dominates(defBlock[it]!!, b) }) return false
            vs
        }
        val liveOuts = LinkedHashSet<String>().also { s -> needed.forEach { s.addAll(it) } }
        if (liveOuts.any { !scalar(typeOf[it]!!) }) return false

        // initial values of H's phis: one value from all outside predecessors, or a phi in F's call block
        val outside = g.pred[h]!!.filter { it !in inL }.distinct()
        val hBlock = g.block[h]!!
        val initOf = HashMap<Inst, LValue>()
        val callPhis = ArrayList<Inst>()                          // phis of F's call block, passed to M
        val id = seq++
        for (phi in hBlock.insts) {
            if (phi.op != "phi") break
            val vals = outside.map { p -> phi.incoming.firstOrNull { it.second == p }?.first ?: return false }
            if (vals.all { same(it, vals[0]) }) { initOf[phi] = vals[0]; continue }
            if (!scalar(phi.ty)) return false
            val cp = Inst("\u0004i$id.${callPhis.size}", "phi").also { it.ty = phi.ty }
            outside.forEachIndexed { k, p -> cp.incoming.add(vals[k] to p) }
            callPhis.add(cp); initOf[phi] = Local(cp.res!!)
        }

        // ---- M's blocks: entry, L (exit edges retargeted), copies of the dead ends, exit blocks
        val entry = "\u0004entry"
        val mBlocks = ArrayList<Block>()
        mBlocks.add(Block(entry).also { it.insts.add(Inst(null, "br").also { br -> br.labels.add(h) }) })
        val deadCopies = order.filter { it in dead }.map { copyBlock(g.block[it]!!) }
        val inM = HashSet<String>().also { it.add(entry); it.addAll(inL); it.addAll(dead) }
        val exitName = HashMap<Pair<String, String>, String>()
        exits.forEachIndexed { k, ex -> exitName[ex] = "\u0004x$k" }

        val retVal = exits.size == 1 && liveOuts.size == 1
        val retType: LType = when { exits.size > 1 -> IntT(32); retVal -> typeOf[liveOuts.first()]!!; else -> VoidT }

        // ---- live-ins (computed on M's final instructions, before anything is changed)
        val newHeaderPhis = HashMap<Inst, ArrayList<Pair<LValue, String>>>()
        for (phi in hBlock.insts) {
            if (phi.op != "phi") break
            newHeaderPhis[phi] = ArrayList(phi.incoming.filter { it.second in inL }).also { it.add(initOf[phi]!! to entry) }
        }
        val defM = HashSet<String>().also { s -> s.addAll(defL); deadCopies.forEach { b -> b.insts.forEach { i -> i.res?.let { s.add(it) } } } }
        val callPhiNames = callPhis.map { it.res!! }.toSet()
        val liveIns = LinkedHashSet<String>()
        fun liveIn(v: LValue) { if (v is Local && v.name !in defM && v.name !in callPhiNames) liveIns.add(v.name) }
        for (b in order) if (b in inL) for (i in g.block[b]!!.insts) {
            if (i.op == "phi") { (newHeaderPhis[i] ?: i.incoming).forEach { liveIn(it.first) }; continue }
            i.ops.forEach { liveIn(it.v) }; i.callee?.let { liveIn(it) }
        }
        for (b in deadCopies) for (i in b.insts) {
            if (i.op == "phi") { i.incoming.filter { it.second in inM }.forEach { liveIn(it.first) }; continue }
            i.ops.forEach { liveIn(it.v) }; i.callee?.let { liveIn(it) }
        }
        val params = liveIns.map { it to (typeOf[it] ?: return false) } + callPhis.map { it.res!! to it.ty }
        if (params.any { !scalar(it.second) } || params.sumOf { slots(it.second) } > MAX_PARAM_SLOTS) return false

        // ---- commit: build M
        val fieldOf = HashMap<String, String>()
        if (!retVal) for (v in liveOuts) { val n = "o$${fields.size}"; fields[n] = typeOf[v]!!; fieldOf[v] = n }
        var mName = "${f.name}\$L$id"
        while (m.funcs.containsKey(mName)) mName += "_"
        val mf = LFunc(mName, retType, params.map { it.second }, params.map { it.first }, false)
        for (b in order) if (b in inL) {
            val blk = g.block[b]!!
            for (i in blk.insts) {
                newHeaderPhis[i]?.let { i.incoming.clear(); i.incoming.addAll(it) }
                if (i.op == "br" || i.op == "switch") retarget(i) { s -> exitName[b to s] ?: s }
            }
            mBlocks.add(blk)
        }
        for (b in deadCopies) {
            for (phi in b.insts) { if (phi.op != "phi") break; phi.incoming.retainAll { it.second in inM } }
            mBlocks.add(b)
        }
        exits.forEachIndexed { k, _ ->
            val xb = Block(exitName[exits[k]]!!)
            if (!retVal) for (v in needed[k]) xb.insts.add(Inst(null, "kx.put").also { it.pred = fieldOf[v]!!; it.ops.add(TV(typeOf[v]!!, Local(v))) })
            xb.insts.add(Inst(null, "ret").also { r ->
                when {
                    exits.size > 1 -> r.ops.add(TV(IntT(32), CInt(k.toLong())))
                    retVal -> liveOuts.first().let { v -> r.ops.add(TV(typeOf[v]!!, Local(v))) }
                }
            })
            mBlocks.add(xb)
        }
        mf.blocks.addAll(mBlocks)
        m.funcs[mName] = mf

        // ---- F: call block and one block per exit edge in place of L
        val callB = Block("\u0004c$id")
        callB.insts.addAll(callPhis)
        val res = when { exits.size > 1 -> "\u0004r$id"; retVal -> liveOuts.first(); else -> null }
        callB.insts.add(Inst(res, "call").also { c ->
            c.callee = GlobalRef(mName); c.fnTy = mf.type; c.ty = retType
            params.forEach { (n, t) -> c.ops.add(TV(t, Local(n))) }
        })
        if (!retVal) for (v in liveOuts) callB.insts.add(Inst(v, "kx.get").also { it.ty = typeOf[v]!!; it.pred = fieldOf[v]!! })
        val yNames = exits.indices.map { "\u0004y$id.$it" }
        callB.insts.add(when (exits.size) {
            0 -> Inst(null, "unreachable")
            1 -> Inst(null, "br").also { it.labels.add(yNames[0]) }
            else -> Inst(null, "switch").also { sw ->
                sw.ops.add(TV(IntT(32), Local(res!!))); sw.labels.add(yNames.last())
                for (k in 0 until exits.size - 1) sw.cases.add(k.toLong() to yNames[k])
            }
        })
        val yBlocks = exits.mapIndexed { k, (_, e) -> Block(yNames[k]).also { it.insts.add(Inst(null, "br").also { br -> br.labels.add(e) }) } }
        exits.forEachIndexed { k, (b, e) ->
            for (phi in g.block[e]!!.insts) {
                if (phi.op != "phi") break
                for (j in phi.incoming.indices) if (phi.incoming[j].second == b) phi.incoming[j] = phi.incoming[j].first to yNames[k]
            }
        }
        for (p in outside) g.block[p]!!.insts.last().let { t -> retarget(t) { s -> if (s == h) callB.name else s } }
        val nb = ArrayList<Block>()
        for (b in f.blocks) {
            if (b.name == h) { nb.add(callB); nb.addAll(yBlocks) }
            if (b.name !in inL) nb.add(b)
        }
        f.blocks.clear(); f.blocks.addAll(nb)
        removeUnreachable(f)
        return true
    }

    /** Blocks reachable from [s] if none of them can return or reach the loop [inL] (and they are few), else null. */
    private fun deadRegion(g: Graph, s: String, inL: Set<String>): Set<String>? {
        val seen = linkedSetOf(s); val work = ArrayDeque(listOf(s))
        while (work.isNotEmpty()) {
            val x = work.removeLast()
            val b = g.block[x]!!
            if (x in inL || b.insts.last().op == "ret" || !movable(b)) return null
            for (y in g.succ[x]!!) if (seen.add(y)) { if (seen.size > MAX_DEAD) return null; work.add(y) }
        }
        return seen
    }

    private fun movable(b: Block) = b.insts.all { i ->
        i.op != "alloca" && i.op != "va_arg" &&
            !(i.op == "call" && (i.callee as? GlobalRef)?.name?.let { n -> FRAME_CALLS.any { n.startsWith(it) } } == true)
    }

    private fun scalar(t: LType) = when (val r = t.r()) { is IntT -> r.bits <= 64; PtrT, FloatT, DoubleT -> true; else -> false }
    private fun slots(t: LType) = when (val r = t.r()) { is IntT -> if (r.bits > 32) 2 else 1; PtrT, DoubleT -> 2; else -> 1 }

    private fun same(a: LValue, b: LValue): Boolean = a === b || when (a) {
        is Local -> b is Local && a.name == b.name
        is GlobalRef -> b is GlobalRef && a.name == b.name
        is CInt -> b is CInt && a.v == b.v
        is CFloat -> b is CFloat && a.v.toRawBits() == b.v.toRawBits()
        else -> false
    }
}

private fun succs(b: Block): List<String> {
    val t = b.insts.lastOrNull() ?: return emptyList()
    return when (t.op) {
        "br" -> t.labels.distinct()
        "switch" -> (t.labels + t.cases.map { it.second }).distinct()
        else -> emptyList()
    }
}

private fun retarget(t: Inst, to: (String) -> String) {
    for (k in t.labels.indices) t.labels[k] = to(t.labels[k])
    for (k in t.cases.indices) t.cases[k] = t.cases[k].first to to(t.cases[k].second)
}

private fun reversePostorder(entry: String, succ: Map<String, List<String>>): List<String> {
    val post = ArrayList<String>(); val seen = hashSetOf(entry)
    val stack = ArrayDeque<Pair<String, Int>>(); stack.addLast(entry to 0)
    while (stack.isNotEmpty()) {
        val (n, k) = stack.removeLast()
        val ss = succ[n] ?: emptyList()
        if (k < ss.size) {
            stack.addLast(n to k + 1)
            if (seen.add(ss[k])) stack.addLast(ss[k] to 0)
        } else post.add(n)
    }
    return post.asReversed()
}

/** Drops blocks that cannot be reached from the entry and phi entries of edges that no longer exist. */
private fun removeUnreachable(f: LFunc) {
    val succ = f.blocks.associate { it.name to succs(it) }
    val live = reversePostorder(f.blocks[0].name, succ).toHashSet()
    if (live.size != f.blocks.size) f.blocks.retainAll { it.name in live }
    val preds = HashMap<String, HashSet<String>>()
    for (b in f.blocks) for (s in succ[b.name]!!) preds.getOrPut(s) { HashSet() }.add(b.name)
    for (b in f.blocks) for (phi in b.insts) {
        if (phi.op != "phi") break
        val ps = preds[b.name] ?: emptySet()
        phi.incoming.retainAll { it.second in ps }
    }
}

private fun copyBlock(b: Block) = Block(b.name).also { nb ->
    for (i in b.insts) nb.insts.add(Inst(i.res, i.op).also { n ->
        n.ty = i.ty; n.ops.addAll(i.ops); n.pred = i.pred; n.samesign = i.samesign; n.labels.addAll(i.labels)
        n.cases.addAll(i.cases); n.incoming.addAll(i.incoming); n.srcElem = i.srcElem; n.indices = i.indices
        n.callee = i.callee; n.fnTy = i.fnTy; n.line = i.line
    })
}
