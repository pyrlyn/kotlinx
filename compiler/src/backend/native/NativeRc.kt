package newkotlin

// Reference counting (Perceus): retain/release emission, ownership transfer, traced fields and cycle slots, reuse planning and scope drops.
// Members of [NativeBackend], split out by concern (extension functions on the backend instance).

internal fun NativeBackend.releaseFn(t: Ty): String? = when (t) {
    is Ty.Cls -> if (t.name in poly) "@rt_release_dyn" else "@rel.${t.name}"
    is Ty.ListTy -> "@rt_list_release"
    is Ty.MapTy -> "@rt_map_release"
    is Ty.Iface, is Ty.NBox -> "@rt_release_dyn"
    Ty.SB -> "@rt_sb_release"
    Ty.Str -> "@rt_str_release"
    is Ty.Arr -> "@rt_arr_release"
    else -> null
}

internal fun NativeBackend.retain(v: String, t: Ty) = emit("call void @${when {
    t.hasPrefixRc -> "rt_hdr_retain"
    !deferredRc -> "rt_retain"
    t is Ty.Cls && t.name !in poly -> if (t.name in cyclic) "rt_gc_log_inc" else "rt_retain"
    t is Ty.Cls || t is Ty.Iface -> "rt_retain_dyn"   // class instance of unknown dynamic class
    ownedColl(t) != null -> "rt_${ownedColl(t)}_retain_gc"   // a second reference stops its traversal: log the owner
    else -> "rt_retain"                                // lists, maps, builders: runtime objects, never traced
}}(ptr $v)")

internal fun NativeBackend.release(v: Val) { releaseFn(v.t)?.let { emit("call void $it(ptr ${v.v})") } }

internal fun NativeBackend.releaseIfOwned(v: Val) { if (v.owned && v.t.isRef) release(v) }

/** Turns a value into a +1 reference that can be stored. */
internal fun NativeBackend.consume(v: Val): Val {
    if (!v.t.isRef || v.owned) return v
    retain(v.v, v.t); return Val(v.v, v.t, true)
}

internal fun NativeBackend.isTraced(t: Ty) = t is Ty.Cls || t is Ty.Iface

/**
 * An ArrayList field of objects is traversed by the collector through its elements (runtime rt_list_trav; only
 * while the list has no other reference). The list (or map) records the traced objects whose fields hold it
 * (rt_list_hold / rt_list_disown), so a release that leaves one reference makes that holder a possible root
 * (runtime.c, Holders). With deferred RC the collector runs next to the mutator: the list is traversed only
 * from its owner (rt_list_own, rt_list_trav_owned), and every change to it logs the owner as modified.
 */
internal fun NativeBackend.tracedList(t: Ty) = (t is Ty.ListTy && isTraced(t.elem)) || (t is Ty.MapTy && isTraced(t.v))

/** Runtime entry point for a traced list or map field ("list" / "map"), else null. */
internal fun NativeBackend.heldColl(t: Ty) = if (tracedList(t)) (if (t is Ty.MapTy) "map" else "list") else null

/** Deferred RC: runtime entry point for a traced list or map field ("list" / "map"), else null. */
internal fun NativeBackend.ownedColl(t: Ty) = if (deferredRc) heldColl(t) else null

/** Call that records traced object [obj] as a holder of collection [v] stored in a field of type [t]. */
internal fun NativeBackend.holdCall(t: Ty, v: String, obj: String) =
    heldColl(t)?.let { "call void @rt_${it}_${if (deferredRc) "own" else "hold"}(ptr $v, ptr $obj)" }

/**
 * The is-cyclic vtable slot of cyclic class [c]. Non-null marks the class cyclic. Native and wasm: when every traced
 * field is a plain class/interface reference (no traced list or map), it points to @lay.C = { i32 n, i32 object
 * size, i32 relo needed (0/1), [n x i32] byte offsets of those fields }: trial deletion
 * (native/gc/zig/src/common/trial_deletion.zig) reads the children itself instead of calling @trav.C with a visitor
 * (two indirect calls per object and child), and perceus frees garbage without @relo.C / @free.C calls.
 * Otherwise, and on --target=js (size), it is 1 and the collector calls @trav.C.
 */
internal fun NativeBackend.cyclicSlot(c: ClassInfo): String {
    if (rc32() || c.fields.any { (_, t) -> tracedList(t) }) return "inttoptr (i64 1 to ptr)"
    val offs = c.fields.mapIndexedNotNull { i, (_, t) ->
        if (isTraced(t)) "i32 ptrtoint (ptr getelementptr (${c.llType}, ptr null, i32 0, i32 ${i + 2}) to i32)" else null
    }
    // Same fields as @relo.C releases (genClass).
    val relo = c.fields.any { (_, t) -> ownedColl(t) != null || (!isTraced(t) && releaseFn(t) != null) }
    val size = "i32 ptrtoint (ptr getelementptr (${c.llType}, ptr null, i32 1) to i32)"
    globals.append("@lay.${c.name} = internal constant { i32, i32, i32, [${offs.size} x i32] } { i32 ${offs.size}, $size, i32 ${if (relo) 1 else 0}, [${offs.size} x i32] [${offs.joinToString(", ")}] }, align 4\n")
    return "@lay.${c.name}"
}

/** Before a dying cyclic object (%p) releases collection field value [v]: it no longer holds it. */
internal fun NativeBackend.disown(c: ClassInfo, t: Ty, v: String) =
    if (c.name in cyclic) heldColl(t)?.let { "  call void @rt_${it}_disown(ptr $v, ptr %p)\n" } ?: "" else ""

/** @relf.C: releases the reference fields of a dying C whose memory is reused (FBIP). */
internal fun NativeBackend.relfFields(c: ClassInfo) {
    val f = StringBuilder("define internal void @relf.${c.name}(ptr %p) {\nentry:\n")
    c.fields.forEachIndexed { i, (_, t) ->
        releaseFn(t)?.let { fn -> f.append("  %f$i = getelementptr ${c.llType}, ptr %p, i32 0, i32 ${i + 2}\n  %v$i = load ptr, ptr %f$i\n${disown(c, t, "%v$i")}  call void $fn(ptr %v$i)\n") }
    }
    f.append("  ret void\n}\n\n")
    funcs.append(f)
}

/**
 * FBIP reuse: if the statement's value is a constructor call and a block-local object dies in this
 * statement (all its uses are inside the constructor's arguments), the new object may take its memory.
 */
internal fun NativeBackend.planReuse(s: S, i: Int, uses: List<List<Use>>, last: Map<String, Int>, scope: Scope) {
    reuseFor.clear()
    val top = when (s) { is VarS -> s.init; is AssignS -> if (s.op == "=") s.value else null; is RetS -> s.e; else -> null }
    val ci = ctorClass(top) ?: return
    val call = top as CallE
    val inArgs = HashSet<NameE>()
    call.args.forEach { a -> Uses.ofStmt(ExprS(a)).forEach { inArgs.add(it.node) } }
    val layout = ci.fields.map { it.second.llvm }
    for ((n, li) in last) {
        if (li != i) continue
        val l = scope.vars[n] ?: continue
        if (l !in scope.refs) continue
        val t = l.t as? Ty.Cls ?: continue
        val old = classes[t.name] ?: continue
        if (old.name in poly || ci.name in poly || old.ancestors.isNotEmpty()) continue // static type may not be the dynamic one
        if (old.fields.map { it.second.llvm } != layout) continue
        if ((old.name in cyclic) != (ci.name in cyclic)) continue // reuse stays within one region
        if (deferredRc && ci.name in cyclic) continue // deferred RC: the header count is not current
        if (uses[i].any { it.node.n == n && it.node !in inArgs }) continue
        if (uses[i].count { it.node.n == n } == 1 && uses[i].first { it.node.n == n }.let { u -> call.args.any { it === u.node } }) continue // moved instead
        reuseFor[call] = l
        return
    }
}

/** Releases a local's reference and clears its slot (Perceus drop after the last use). */
internal fun NativeBackend.dropLocal(l: Local) {
    release(Val(emitT("load ptr, ptr ${l.slot}"), l.t))
    emit("store ptr null, ptr ${l.slot}")
}

internal fun NativeBackend.releaseScope(s: Scope) { for (l in s.refs) release(Val(emitT("load ptr, ptr ${l.slot}"), l.t)) }

internal fun NativeBackend.releaseAll() { for (s in scopes.reversed()) releaseScope(s) }

/** A `return` inside a call's arguments (`foo(x, return q)`, kt49092): the owned arguments before it die. */
internal fun NativeBackend.releaseArgTemps() { for (k in argTemps.size - 1 downTo 0) argTemps[k].let { (v, _, b) -> if (b === body) releaseIfOwned(v) } }
