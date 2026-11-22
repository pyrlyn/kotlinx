package newkotlin

// Built-in members of standard types (strings, collections, data classes, ranges, chars) and their lowering.
// Members of [NativeBackend], split out by concern (extension functions on the backend instance).

/**
 * `for ((k, v) in m)` / `for (e in m)` (`e.key`, `e.value`) over a map, in insertion order: the destructured
 * components and the entry's key/value read the entry at the current position (borrowed from the map).
 */
internal fun NativeBackend.mapFor(coll: Val, t: Ty.MapTy, s: ForS, lc: String, lb: String, ls: String, le: String) {
    if (coll.owned) declare("\$coll${tmpN++}", coll.t, false, true).let { emit("store ptr ${coll.v}, ptr ${it.slot}") }
    val kn = "\$mk${tmpN++}"; val vn = "\$mv${tmpN++}"
    fun isEntry(x: E) = x is NameE && x.n == s.name
    val body = Subst(emptyMap(), rw = { x ->
        when {
            x is CallE && x.args.isEmpty() && x.lambda == null && x.callee is MemberE && isEntry(x.callee.recv) && x.callee.n == "component1" -> NameE(kn, line = x.line)
            x is CallE && x.args.isEmpty() && x.lambda == null && x.callee is MemberE && isEntry(x.callee.recv) && x.callee.n == "component2" -> NameE(vn, line = x.line)
            x is MemberE && isEntry(x.recv) && x.n == "key" -> NameE(kn, line = x.line)
            x is MemberE && isEntry(x.recv) && x.n == "value" -> NameE(vn, line = x.line)
            x is NameE && isEntry(x) -> fail("a map entry is only supported through key/value or destructuring")
            else -> null
        }
    }).block(s.body)
    val idx = "%l.idx.${tmpN++}"
    allocas.append("  $idx = alloca i64\n")
    emit("store i64 0, ptr $idx")
    val kl = declare(kn, t.k, false, false); val vl = declare(vn, t.v, false, false)
    br(lc); startBlock(lc)
    val i = emitT("load i64, ptr $idx")
    condBr(emitT("icmp slt i64 $i, ${emitT("call i64 @rt_map_end(ptr ${coll.v})")}"), lb, le)
    startBlock(lb)
    val live = label("mfor.live")
    condBr(emitT("icmp ne i32 ${emitT("call i32 @rt_map_live(ptr ${coll.v}, i64 $i)")}, 0"), live, ls)
    startBlock(live)
    emit("store ${t.k.llvm} ${fromSlot(emitT("call i64 @rt_map_key_at(ptr ${coll.v}, i64 $i)"), t.k)}, ptr ${kl.slot}")
    emit("store ${t.v.llvm} ${fromSlot(emitT("call i64 @rt_map_val_at(ptr ${coll.v}, i64 $i)"), t.v)}, ptr ${vl.slot}")
    pushLoop(Triple(ls, le, scopes.size))
    genBlockBody(body, false)
    popLoop()
    if (!term) br(ls)
    startBlock(ls)
    emit("store i64 ${emitT("add i64 ${emitT("load i64, ptr $idx")}, 1")}, ptr $idx")
    br(lc)
    startBlock(le)
    releaseIfOwned(coll)
}

/** `m.keys` / `m.values`: a new list of the map's keys or values in insertion order. */
internal fun NativeBackend.mapToList(m: Val, t: Ty.MapTy, keys: Boolean): Val {
    val et = if (keys) t.k else t.v
    val l = emitT("call ptr @rt_list_new(ptr ${releaseFn(et) ?: "null"})")
    val idx = "%l.idx.${tmpN++}"
    allocas.append("  $idx = alloca i64\n")
    emit("store i64 0, ptr $idx")
    val lc = label("mkeys.cond"); val lb = label("mkeys.body"); val ll = label("mkeys.live"); val ls = label("mkeys.step"); val le = label("mkeys.end")
    br(lc); startBlock(lc)
    val i = emitT("load i64, ptr $idx")
    condBr(emitT("icmp slt i64 $i, ${emitT("call i64 @rt_map_end(ptr ${m.v})")}"), lb, le)
    startBlock(lb)
    condBr(emitT("icmp ne i32 ${emitT("call i32 @rt_map_live(ptr ${m.v}, i64 $i)")}, 0"), ll, ls)
    startBlock(ll)
    val x = emitT("call i64 @rt_map_${if (keys) "key" else "val"}_at(ptr ${m.v}, i64 $i)")
    if (et.isRef) retain(fromSlot(x, et), et)
    emit("call void @rt_list_add(ptr $l, i64 $x)")
    br(ls); startBlock(ls)
    emit("store i64 ${emitT("add i64 ${emitT("load i64, ptr $idx")}, 1")}, ptr $idx")
    br(lc)
    startBlock(le)
    releaseIfOwned(m)
    return Val(l, Ty.ListTy(et), true)
}

internal fun NativeBackend.isData(t: Ty) = t is Ty.Cls && "\$equals" in classes.getValue(t.name).methods

/** Generated members (`copy`, default `equals`/`hashCode`/`toString`) and built-in `hashCode`/`componentN`; null if not one. */
internal fun NativeBackend.dataMethod(r: Val, n: String, e: CallE): Val? {
    val t = r.t
    if (t is Ty.Cls && !hasMember(t, n)) {
        when (n) {
            "copy" -> if (isData(t)) {
                val cd = classDecls.getValue(t.name)
                if (cd.ctorParams.any { p -> cd.fields.none { it.name == p.name } }) fail("copy() of a data class with plain constructor parameters is not supported")
                val named = e.args.filterIsInstance<NamedE>().associateBy { it.name }
                named.keys.forEach { k -> if (cd.ctorParams.none { it.name == k }) fail("unknown copy() parameter $k") }
                val pos = e.args.filter { it !is NamedE }
                return withTemps(listOf(r)) { (x) ->
                    val args = cd.ctorParams.mapIndexed { i, p -> pos.getOrNull(i) ?: named[p.name]?.e ?: MemberE(x, p.name, false, line = line) }
                    genNamedCall(t.name, CallE(NameE(t.name), emptyList(), args, null, line = line))
                }
            }
            "equals" -> return compare(BinOp.EQ, r, genExpr(e.args.single()))
            "hashCode" -> { val v = Val(emitT("trunc i64 ${emitT("ptrtoint ptr ${r.v} to i64")} to i32"), Ty.I32); releaseIfOwned(r); return v }
            "toString" -> return sbMsg(r)
        }
    }
    if (n == "compareTo" && e.args.size == 1 && (t == Ty.Str || t.isNum || t == Ty.Char)) {
        val o = genExpr(e.args[0])
        if (t == Ty.Str) { val c = emitT("call i32 @rt_str_cmp(ptr ${r.v}, ptr ${coerce(o, Ty.Str).v})"); releaseIfOwned(r); releaseIfOwned(o); return Val(c, Ty.I32) }
        if (t.isFp || o.t.isFp) {
            // Total order (Double.compare): -0.0 < 0.0, NaN is greatest and equal to itself.
            val x = coerce(r, Ty.F64).v; val y = coerce(o, Ty.F64).v
            fun bits(d: String) = emitT("select i1 ${emitT("fcmp uno double $d, $d")}, i64 9221120237041090560, i64 ${emitT("bitcast double $d to i64")}")
            val bx = bits(x); val by = bits(y)
            val bc = emitT("select i1 ${emitT("icmp slt i64 $bx, $by")}, i32 -1, i32 ${emitT("zext i1 ${emitT("icmp ne i64 $bx, $by")} to i32")}")
            val gt = emitT("select i1 ${emitT("fcmp ogt double $x, $y")}, i32 1, i32 $bc")
            return Val(emitT("select i1 ${emitT("fcmp olt double $x, $y")}, i32 -1, i32 $gt"), Ty.I32)
        }
        val lt = coerce(compare(BinOp.LT, r, o), Ty.I1).v; val gt = coerce(compare(BinOp.GT, r, o), Ty.I1).v
        return Val(emitT("select i1 $lt, i32 -1, i32 ${emitT("zext i1 $gt to i32")}"), Ty.I32)
    }
    if (n == "hashCode" && e.args.isEmpty()) when {
        t == Ty.Str -> { val v = Val(emitT("call i32 @rt_str_hash(ptr ${r.v})"), Ty.I32); releaseIfOwned(r); return v }
        t == Ty.I1 -> return Val(emitT("select i1 ${r.v}, i32 1231, i32 1237"), Ty.I32)
        t == Ty.F64 -> return Val(emitT("call i32 @rt_double_hash(double ${r.v})"), Ty.I32)
        t == Ty.F32 -> return Val(emitT("select i1 ${emitT("fcmp uno float ${r.v}, ${r.v}")}, i32 2143289344, i32 ${emitT("bitcast float ${r.v} to i32")}"), Ty.I32)
        t == Ty.I64 || t == Ty.U64 -> { val x = emitT("xor i64 ${r.v}, ${emitT("lshr i64 ${r.v}, 32")}"); return Val(emitT("trunc i64 $x to i32"), Ty.I32) }
        t == Ty.Char || t.isInt -> return Val(coerce(Val(r.v, if (t == Ty.Char) Ty.I32 else t), Ty.I32).v, Ty.I32)
        // Arrays hash by identity, as in Kotlin (`contentHashCode()` is the structural one).
        t is Ty.Arr -> { val v = Val(emitT("trunc i64 ${emitT("ptrtoint ptr ${r.v} to i64")} to i32"), Ty.I32); releaseIfOwned(r); return v }
    }
    if (t is Ty.ListTy && n == "hashCode" && e.args.isEmpty()) {
        // 31 * h + element hash over the elements, starting from 1 (null elements hash to 0).
        val h = "%l.lh.${tmpN++}"; val idx = "%l.lhi.${tmpN++}"
        allocas.append("  $h = alloca i32\n  $idx = alloca i64\n")
        emit("store i32 1, ptr $h"); emit("store i64 0, ptr $idx")
        val lc = label("lh.cond"); val lb = label("lh.body"); val le = label("lh.end")
        val n0 = emitT("call i64 @rt_list_size(ptr ${r.v})")
        br(lc); startBlock(lc)
        val i = emitT("load i64, ptr $idx")
        condBr(emitT("icmp slt i64 $i, $n0"), lb, le)
        startBlock(lb)
        val et = t.elem
        val ev = Val(fromSlot(emitT("call i64 @rt_list_get(ptr ${r.v}, i64 $i)"), et), et, false)
        val eh = if (et.llvm == "ptr") {
            val ln = label("lh.nn"); val lj = label("lh.join"); val from = cur
            condBr(emitT("icmp eq ptr ${ev.v}, null"), lj, ln)
            startBlock(ln)
            val x = coerce(method(ev, "hashCode", CallE(e.callee, emptyList(), emptyList(), null, line = line)), Ty.I32)
            val fromN = cur
            br(lj); startBlock(lj)
            emitT("phi i32 [ 0, %$from ], [ ${x.v}, %$fromN ]")
        } else coerce(method(ev, "hashCode", CallE(e.callee, emptyList(), emptyList(), null, line = line)), Ty.I32).v
        emit("store i32 ${emitT("add i32 ${emitT("mul i32 ${emitT("load i32, ptr $h")}, 31")}, $eh")}, ptr $h")
        emit("store i64 ${emitT("add i64 $i, 1")}, ptr $idx")
        br(lc); startBlock(le)
        val v = Val(emitT("load i32, ptr $h"), Ty.I32)
        releaseIfOwned(r); return v
    }
    if (t is Ty.ListTy && e.args.isEmpty() && n.startsWith("component") && n.removePrefix("component").toIntOrNull() != null)
        return method(r, "get", CallE(e.callee, emptyList(), listOf(IntL(n.removePrefix("component").toLong() - 1, false)), null, line = line))
    return null
}

internal fun NativeBackend.method(r: Val, n: String, e0: CallE): Val {
    val t = r.t
    if (t is Ty.NBox && t.inner.isNum && n == "\$bits" && e0.args.isEmpty()) {
        // Data class equality on `Double?`: canonical bits, null -> a pattern no canonical Double has.
        val from = cur; val lv = label("nb.val"); val le = label("nb.end")
        condBr(emitT("icmp eq ptr ${r.v}, null"), le, lv)
        startBlock(lv)
        val x = unbox(Val(r.v, t, false), t.inner)
        val b = if (x.t == Ty.F64) Val(doubleBits(x), Ty.I64) else coerce(x, Ty.I64); val vEnd = cur
        br(le); startBlock(le)
        val res = Val(emitT("phi i64 [ 9221120237041090561, %$from ], [ ${b.v}, %$vEnd ]"), Ty.I64)
        releaseIfOwned(r); return res
    }
    if (t is Ty.NBox && n == "hashCode" && e0.args.isEmpty()) {
        // null -> 0, else the value's hashCode.
        val from = cur; val lv = label("nh.val"); val le = label("nh.end")
        condBr(emitT("icmp eq ptr ${r.v}, null"), le, lv)
        startBlock(lv)
        val h = coerce(method(unbox(Val(r.v, t, false), t.inner), n, e0), Ty.I32); val vEnd = cur
        br(le); startBlock(le)
        val res = Val(emitT("phi i32 [ 0, %$from ], [ ${h.v}, %$vEnd ]"), Ty.I32)
        releaseIfOwned(r); return res
    }
    // `isPermanent()` (kotlin.native.internal): an object is permanent (statically initialized) when it has no
    // initializer code other than `const val`s.
    if (n == "isPermanent" && e0.args.isEmpty() && t is Ty.Cls && prog.globals.any { it.name == "\$o\$${t.name}" }) {
        val c = classDecls.getValue(t.name)
        releaseIfOwned(r)
        val permanent = c.inits.all { it is FieldD && "${c.name}.${it.name}" in ConstFields.names }
        return Val(if (permanent) "true" else "false", Ty.I1)
    }
    if (n == "collectReferenceFieldValues" && e0.args.isEmpty() && e0.lambda == null) return collectRefFields(r)
    // Property delegate (see Parser.provided): `provideDelegate(thisRef, p)` if the delegate has that operator.
    if (n == "\$provideDelegate") {
        val m = "provideDelegate"
        val has = hasMember(t, m) || extFuns[m]?.any { extMatches(t, it.params[0].type, it.typeParams) } == true || hasMext(m, t)
        return if (has) method(r, m, CallE(MemberE((e0.callee as MemberE).recv, m, false, line = e0.line), emptyList(), e0.args, null, line = e0.line)) else r
    }
    // A property reference is itself a delegate (`val y by ::x`, `by a::x`, `by A::x`): `getValue(thisRef, p)` is
    // `get()`, or `get(thisRef)` for an unbound reference; `setValue` likewise calls `set`. These are stdlib
    // extensions, so a user extension on `Any`/`Any?`/`T` takes precedence (not supported on references: an error).
    if (t is Ty.Cls && t.name.startsWith("\$PRef") && extFuns[n].orEmpty().none { it.params[0].type.name == "Any" || it.params[0].type.name in it.typeParams } && (n == "getValue" && e0.args.size == 2 || n == "setValue" && e0.args.size == 3) && e0.args.none { it is NamedE }) {
        val unbound = methodSigs["${t.name}.get"]?.params?.size == 1
        val args = (if (unbound) e0.args.take(1) else emptyList()) + e0.args.drop(2)
        return method(r, if (n == "getValue") "get" else "set", CallE(e0.callee, emptyList(), args, null, line = e0.line))
    }
    // Member extension of the enclosing class (dispatch receiver `this`).
    owner?.let { o -> o.methods["mext\$$n"]?.takeIf { extMatches(t, it.params[0].type, it.typeParams) && !hasMember(t, n) }?.let {
        return withTemps(listOf(r)) { (x) -> callMethod(Val("%this", Ty.Cls(o.name)), o, "mext\$$n", CallE(NameE("mext\$$n"), e0.typeArgs, listOf(x) + e0.args, e0.lambda, line = line)) }
    } }
    // Member extension of the companion object of the enclosing class or of a class it is nested in (an implicit receiver).
    owner?.let { o -> companionMext(o.name, n, t)?.let { cn ->
        return withTemps(listOf(r)) { (x) -> method(genExpr(NameE(cn, line = line)), "mext\$$n", CallE(NameE("mext\$$n"), e0.typeArgs, listOf(x) + e0.args, e0.lambda, line = line)) }
    } }
    // Member extension of an implicit receiver (`with(obj) { x.ext() }`, a lambda with receiver).
    lookup("this")?.let { l -> val rt = l.t
        if (rt is Ty.Cls && rt.name != owner?.name) classes[rt.name]?.let { o -> o.methods["mext\$$n"]?.takeIf { extMatches(t, it.params[0].type, it.typeParams) && !hasMember(t, n) }?.let {
            return withTemps(listOf(r)) { (x) -> method(genExpr(NameE("this", line = line)), "mext\$$n", CallE(NameE("mext\$$n"), e0.typeArgs, listOf(x) + e0.args, e0.lambda, line = line)) }
        } }
        if (rt is Ty.Iface && ifaces[rt.name]?.methods?.get("mext\$$n")?.params?.firstOrNull() == t && !hasMember(t, n))
            return withTemps(listOf(r)) { (x) -> method(genExpr(NameE("this", line = line)), "mext\$$n", CallE(NameE("mext\$$n"), e0.typeArgs, listOf(x) + e0.args, e0.lambda, line = line)) }
    }
    if (isAny(t) && n == "toString" && e0.args.isEmpty()) { val s = anyCall("anyToString__", listOf(Val(r.v, t, false))); releaseIfOwned(r); return s }
    if (isAny(t) && n == "equals" && e0.args.size == 1) return compare(BinOp.EQ, r, genExpr(e0.args[0]))
    if (isAny(t) && e0.args.isEmpty() && n.startsWith("to") && "numTo${n.drop(2)}__" in funDecls) { val c = anyCall("numTo${n.drop(2)}__", listOf(Val(r.v, t, false))); releaseIfOwned(r); return c }
    if (isAny(t) && n == "hashCode" && e0.args.isEmpty()) { val h = anyCall("anyHashCode__", listOf(Val(r.v, t, false))); releaseIfOwned(r); return h }
    // Internal: canonical bits of a Double (all NaNs equal), used by data class equals.
    if (t.isFp && n == "\$bits" && e0.args.isEmpty()) {
        if (t == Ty.F32) return Val(doubleBits(r), Ty.I64)
        val b = emitT("bitcast double ${r.v} to i64")
        val nan = emitT("fcmp uno double ${r.v}, ${r.v}")
        return Val(emitT("select i1 $nan, i64 9221120237041090560, i64 $b"), Ty.I64)
    }
    // Operators of numbers and strings called as methods: `1.plus(2)`, `"a".plus("b")`, `1.rangeTo(3)`, `x.equals(y)`.
    val opOf = mapOf("plus" to BinOp.PLUS, "minus" to BinOp.MINUS, "times" to BinOp.TIMES, "div" to BinOp.DIV, "rem" to BinOp.REM, "rangeTo" to BinOp.RANGE, "equals" to BinOp.EQ)
    // `equals` is boxed equality: false for another type; Double.equals compares bits (0.0 != -0.0), not supported.
    if (n == "equals" && t.isFp && e0.args.size == 1) fail("$t.equals() is not supported")
    if (n == "equals" && (t == Ty.I32 || t == Ty.I64 || t == Ty.U32 || t == Ty.U64 || t == Ty.Char || t == Ty.Str) && e0.args.size == 1 && e0.args[0] !is NamedE &&
        extFuns[n]?.none { extMatches(t, it.params[0].type, it.typeParams) } != false) {
        val a = genExpr(e0.args[0])
        if (a.t is Ty.NBox && a.t.inner == t) return nboxEq(BinOp.EQ, r, a)
        if (a.t != t) { releaseIfOwned(a); releaseIfOwned(r); return Val("false", Ty.I1) }
        return compare(BinOp.EQ, r, a)
    }
    if (n != "equals" && (t == Ty.I32 || t == Ty.I64 || t == Ty.U32 || t == Ty.U64 || t.isFp || t == Ty.Char || (t == Ty.Str && (n == "plus" || n == "equals"))) &&
        n in opOf && e0.args.size == 1 && e0.args[0] !is NamedE && e0.lambda == null && (!e0.infix || extFuns[n]?.none { extMatches(t, it.params[0].type, it.typeParams) } != false))
        return withTemps(listOf(r)) { (x) -> genExpr(BinE(opOf.getValue(n), x, e0.args[0], line = line)) }
    // Built-in members `inc()`/`dec()` of numbers and chars win over extensions.
    // Byte (I8) is excluded: its inc() must wrap at 8 bits.
    if ((t == Ty.I32 || t == Ty.I64 || t == Ty.U32 || t == Ty.U64 || t == Ty.Char) && (n == "inc" || n == "dec") && e0.args.isEmpty() && !incViaExt)
        return withTemps(listOf(r)) { (x) -> genExpr(BinE(if (n == "inc") BinOp.PLUS else BinOp.MINUS, x, IntL(1, false), line = line)) }
    val nn = nonNullRecv; nonNullRecv = false
    // Bitwise members called as methods: `x.shl(2)`, `x.inv()` (they win over an extension when the receiver is `y!!`).
    if ((t == Ty.I32 || t == Ty.I64 || t == Ty.U32 || t == Ty.U64) && (nn || extFuns[n]?.none { extMatches(t, it.params[0].type, it.typeParams) } != false) && e0.lambda == null && e0.args.none { it is NamedE }) {
        if (n in setOf("and", "or", "xor", "shl", "shr", "ushr") && e0.args.size == 1)
            return withTemps(listOf(r)) { (x) -> genExpr(BinE(BinOp.of(n), x, e0.args[0], line = line)) }
        if (n == "inv" && e0.args.isEmpty())
            return withTemps(listOf(r)) { (x) -> genExpr(BinE(BinOp.XOR, x, UnE(UnOp.NEG, IntL(1, t == Ty.I64 || t == Ty.U64), line = line), line = line)) }
    }
    if (!hasMember(t, n)) extFuns[n]?.firstOrNull { extMatches(t, it.params[0].type, it.typeParams) && !(nn && it.params[0].type.nullable && t.llvm != "ptr") }?.let { f ->
        return withTemps(listOf(r)) { (x) -> genNamedCall(f.name, CallE(NameE(f.name), e0.typeArgs, listOf(x) + e0.args, e0.lambda, line = line)) }
    }
    // `x.p(args)` for a property `p` of function type with a getter (a class getter or an extension property): invoke its value.
    if ((t is Ty.Cls && classes.getValue(t.name).methods.let { "\$prop\$$n" in it && n !in it }) ||
        (!hasMember(t, n) && extFuns["\$prop\$$n"]?.any { it.params.size == 1 && extMatches(t, it.params[0].type, it.typeParams) } == true)) {
        return withTemps(listOf(r)) { (x) ->
            val pe = MemberE(x, n, false, line = line)
            val f = genExpr(pe)
            if (fnSig(f.t) == null) fail("unknown method ${t}.$n()")
            invokeFn(f, CallE(pe, e0.typeArgs, e0.args, e0.lambda, line = line))
        }
    }
    dataMethod(r, n, e0)?.let { return it }
    if (t is Ty.ListTy && n in setOf("map", "filter", "forEach", "sumOf", "count") && e0.lambda == null && e0.args.size == 1 && e0.args[0] !is NamedE) {
        // A function value or callable reference argument becomes an inline lambda `{ f(it) }`.
        val a = e0.args[0]
        val it0 = NameE("it", line = line)
        val tn = ((a as? UnE)?.e as? NameE)?.n
        val call: E = when {
            a is UnE && a.op == UnOp.REF && a.e is NullL && a.ref!! in funDecls -> CallE(NameE(a.ref), emptyList(), listOf(it0), null)
            a is UnE && a.op == UnOp.REF && tn != null && lookup(tn) == null && tn in classes -> {
                val m = a.ref!!
                if (m in classes.getValue(tn).methods && !m.startsWith("\$prop\$")) CallE(MemberE(it0, m, false), emptyList(), emptyList(), null) else MemberE(it0, m, false)
            }
            else -> CallE(a, emptyList(), listOf(it0), null)
        }.also { it.line = line }
        return method(r, n, CallE(e0.callee, e0.typeArgs, emptyList(), Block(listOf(ExprS(call, line = line))), line = e0.line))
    }
    // A trailing lambda on a built-in method that is not an inline lambda builtin is an ordinary last argument.
    val e = if (e0.lambda != null && t !is Ty.Cls && t !is Ty.Iface && !(t is Ty.ListTy && n in setOf("map", "filter", "forEach", "sumOf", "count")) && n != "usePinned")
        CallE(e0.callee, e0.typeArgs, allArgs(e0), null) else e0
    val res: Val = when {
        t.isNum && !t.isFp && (n == "toUByte" || n == "toUShort") -> {
            val w = if (n == "toUByte") "i8" else "i16"
            Val(emitT("zext $w ${emitT("trunc ${t.llvm} ${r.v} to $w")} to i32"), Ty.U32)
        }
        (t.isNum || t == Ty.Char) && n == "toShort" -> {
            val i = if (t == Ty.Char) Val(r.v, Ty.I32) else if (t.isFp) fpToInt(r, Ty.I32) else coerce(r, Ty.I32)
            Val(emitT("sext i16 ${emitT("trunc i32 ${i.v} to i16")} to i32"), Ty.I32)
        }
        (t.isNum && n in setOf("toInt", "toUInt", "toLong", "toULong", "toByte", "toDouble", "toFloat", "toString")) ||
            // `Int?.toString()` too: appendVal prints a null NBox as "null".
            ((t == Ty.Char || t == Ty.I1 || t is Ty.ListTy || t is Ty.Arr || t is Ty.NBox) && n == "toString" && e.args.isEmpty()) -> {
            if (n == "toString") { val sb = emitT("call ptr @rt_sb_new()"); appendVal("rt_sb_", "ptr $sb, ", r); return Val(emitT("call ptr @rt_sb_done(ptr $sb)"), Ty.Str, true) }
            val target = when (n) { "toInt" -> Ty.I32; "toUInt" -> Ty.U32; "toLong" -> Ty.I64; "toULong" -> Ty.U64; "toByte" -> Ty.I8; "toFloat" -> Ty.F32; else -> Ty.F64 }
            when {
                // Kotlin conversions saturate and map NaN to 0 (llvm.fpto[su]i.sat); Byte goes through Int.
                t.isFp && target.isInt -> fpToInt(r, target)
                t == Ty.F64 && target == Ty.F32 -> Val(emitT("fptrunc double ${r.v} to float"), target)
                else -> coerce(r, target)
            }
        }
        t == Ty.RawPtr && n == "toKString" -> Val(emitT("call ptr @rt_str_from_c(ptr ${r.v})"), Ty.Str, true)
        t == Ty.Str && n == "toKString" -> return r
        // A null String (e.g. `T = String?` in a generic) prints as "null"; literals are immortal, so ownership holds.
        t == Ty.Str && n == "toString" -> return if (r.v.startsWith("@")) r else Val(emitT("select i1 ${emitT("icmp eq ptr ${r.v}, null")}, ptr ${cstr("null")}, ptr ${r.v}"), Ty.Str, r.owned)
        t == Ty.Pinned && n == "addressOf" -> {
            val i = coerce(genExpr(e.args.single()), Ty.I64)
            Val(emitT("getelementptr i8, ptr ${emitT("getelementptr i8, ptr ${r.v}, i64 8")}, i64 ${i.v}"), Ty.RawPtr)
        }
        t is Ty.Cls && n !in classes.getValue(t.name).methods && classes.getValue(t.name).index(n) >= 0 && fnSig(classes.getValue(t.name).let { c -> c.fields[c.index(n)].second }) != null -> {
            return invokeFn(property(r, n), e) // property() takes over an owned receiver
        }
        t is Ty.Cls || t is Ty.Iface -> {
            val v = if (t is Ty.Cls) callMethod(r, classes.getValue(t.name), n, e) else callIface(r, ifaces.getValue((t as Ty.Iface).name), n, e)
            releaseIfOwned(r); return v
        }
        t == Ty.Char && (n == "toInt" || n == "code") -> Val(r.v, Ty.I32)
        t.isInt && n == "toChar" -> Val(emitT("and i32 ${coerce(r, Ty.I32).v}, 65535"), Ty.Char)
        t == Ty.SB && n == "append" -> {
            val v = genExpr(e.args.single())
            appendVal("rt_sb_", "ptr ${r.v}, ", v)
            Val(r.v, Ty.SB, false)
        }
        t == Ty.SB && n == "appendLine" && e.args.size <= 1 -> {
            if (e.args.isNotEmpty()) appendVal("rt_sb_", "ptr ${r.v}, ", genExpr(e.args[0]))
            appendVal("rt_sb_", "ptr ${r.v}, ", Val("10", Ty.Char))
            Val(r.v, Ty.SB, false)
        }
        t == Ty.SB && (n == "setLength" && e.args.size == 1 || n == "clear" && e.args.isEmpty()) -> {
            val len = if (n == "clear") "0" else coerce(genExpr(e.args[0]), Ty.I64).v
            emit("call void @rt_sb_set_length(ptr ${r.v}, i64 $len)")
            if (n == "clear") r else Val("", Ty.Unit)
        }
        t == Ty.SB && n == "toString" -> Val(emitT("call ptr @rt_sb_tostring(ptr ${r.v})"), Ty.Str, true)
        t is Ty.MapTy && e.args.isEmpty() && e.lambda == null && n in setOf("isEmpty", "isNotEmpty", "clear") -> when (n) {
            "clear" -> { emit("call void @rt_map_clear(ptr ${r.v})"); Val("", Ty.Unit) }
            else -> Val(emitT("icmp ${if (n == "isEmpty") "eq" else "ne"} i64 ${emitT("call i64 @rt_map_size(ptr ${r.v})")}, 0"), Ty.I1)
        }
        // `remove(k)`: a reference value is handed over to the caller (null when absent).
        t is Ty.MapTy && n == "remove" && e.args.size == 1 -> {
            val kv = genExpr(e.args[0])
            val x = emitT("call i64 @rt_map_remove(ptr ${r.v}, i64 ${mapKey(kv, t)}, i64 0)")
            releaseIfOwned(kv)
            if (t.v.isRef) Val(fromSlot(x, t.v), t.v, true) else Val("", Ty.Unit)
        }
        t is Ty.MapTy && (n == "containsKey" || n == "get" || n == "put" || n == "getOrDefault") -> {
            val kv = genExpr(e.args[0])
            val k = mapKey(kv, t)
            // String keys are retained by the map; an owned temporary key is released after the call.
            when (n) {
                "containsKey" -> Val(emitT("icmp ne i32 ${emitT("call i32 @rt_map_contains(ptr ${r.v}, i64 $k)")}, 0"), Ty.I1)
                "get" -> Val(fromSlot(emitT("call i64 @rt_map_get(ptr ${r.v}, i64 $k)"), t.v), t.v)
                "getOrDefault" -> if (t.v.isRef) {
                    // Result is owned: the default is consumed; a found value is retained and the default released.
                    val d = consume(coerce(genArg(e.args[1]), t.v))
                    val ds = toSlot(d)
                    val vs = emitT("call i64 @rt_map_get_or(ptr ${r.v}, i64 $k, i64 $ds)")
                    val v = fromSlot(vs, t.v)
                    val hit = label("godhit"); val done = label("goddone")
                    condBr(emitT("icmp ne i64 $vs, $ds"), hit, done)
                    startBlock(hit); retain(v, t.v); release(d); br(done)
                    startBlock(done)
                    Val(v, t.v, true)
                } else Val(fromSlot(emitT("call i64 @rt_map_get_or(ptr ${r.v}, i64 $k, i64 ${toSlot(coerce(genExpr(e.args[1]), t.v))})"), t.v), t.v)
                else -> {
                    val v = if (t.v.isRef) consume(coerce(genArg(e.args[1]), t.v)) else coerce(genExpr(e.args[1]), t.v)
                    emit("call void @rt_map_put(ptr ${r.v}, i64 $k, i64 ${toSlot(v)})"); Val("", Ty.Unit)
                }
            }.also { releaseIfOwned(kv) }
        }
        t is Ty.ListTy && e.lambda != null && n in setOf("map", "filter", "forEach", "sumOf", "count") -> listLambda(r, t, n, e.lambda)
        // `sum()` of a numeric list: `sumOf { it }` (Int/Long/Double/... element types).
        t is Ty.ListTy && n == "sum" && e.args.isEmpty() && e.lambda == null && t.elem.isNum ->
            listLambda(r, t, "sumOf", Block(listOf(ExprS(NameE("it", line = line)))))
        t == Ty.Arr(Ty.I8) && n == "usePinned" -> {
            val lam = e.lambda ?: fail("usePinned needs a lambda")
            scopes.add(Scope())
            val it = declare("it", Ty.Pinned, false, false)
            emit("store ptr ${r.v}, ptr ${it.slot}")
            val v = genBlockBody(lam, true)
            scopes.removeAt(scopes.size - 1)
            v
        }
        t is Ty.ListTy && n == "add" -> {
            val v = consume(coerce(argFor(e.args.single(), t.elem, true), t.elem))
            emit("call void @rt_list_add(ptr ${r.v}, i64 ${toSlot(v)})")
            Val("true", Ty.I1)
        }
        t is Ty.ListTy && n == "get" -> {
            val i = coerce(genExpr(e.args.single()), Ty.I64)
            listIdx(r, i)
            val v = Val(fromSlot(emitT("call i64 @rt_list_get(ptr ${r.v}, i64 ${i.v})"), t.elem), t.elem)
            if (r.owned) consume(v) else v
        }
        t is Ty.ListTy && n == "removeAt" -> {
            val i = coerce(genExpr(e.args.single()), Ty.I64)
            Val(fromSlot(emitT("call i64 @rt_list_remove_at(ptr ${r.v}, i64 ${i.v})"), t.elem), t.elem, t.elem.isRef)
        }
        else -> fail("unknown method ${r.t}.$n()")
    }
    releaseIfOwned(r)
    return res
}

/** [recv]: an owned receiver, released if an argument throws. */
internal fun NativeBackend.methodArgs(sig: FunSig, e: CallE, recv: Val? = null): List<Val> {
    val all = allArgs(e)
    if (sig.params.size != all.size) fail("${sig.name} expects ${sig.params.size} arguments")
    val base = argTemps.size
    if (recv != null && recv.owned) argTemps.add(Triple(recv, tryStack.size, body))
    try { return evalArgs(all.size) { i -> coerce(argFor(all[i], sig.params[i]), sig.params[i]) } }
    finally { while (argTemps.size > base) argTemps.removeAt(argTemps.size - 1) }
}

/** Call arguments including a trailing lambda (`f(x) { ... }`). */
internal fun NativeBackend.allArgs(e: CallE): List<E> = if (e.lambda != null) e.args + LambdaE(e.lambda) else e.args

/** Inline expansion of map/filter/forEach/sumOf/count (Kotlin's are inline functions too). */
internal fun NativeBackend.listLambda(r: Val, t: Ty.ListTy, n: String, lam: Block): Val {
    val param = lam.params.firstOrNull() ?: "it"
    val initPos = body.length
    val res = if (n == "map" || n == "filter") tmp() else ""
    val acc = if (n == "sumOf" || n == "count") "%l.acc.${tmpN++}" else ""
    var accTy: Ty = Ty.I32
    var mapTy: Ty = Ty.Unit
    val idx = "%l.idx.${tmpN++}"
    allocas.append("  $idx = alloca i64\n")
    emit("store i64 0, ptr $idx")
    val lc = label("lam.cond"); val lb = label("lam.body"); val ls = label("lam.step"); val le = label("lam.end")
    startBlock(lc)
    val i = emitT("load i64, ptr $idx")
    condBr(emitT("icmp slt i64 $i, ${emitT("call i64 @rt_list_size(ptr ${r.v})")}"), lb, le)
    startBlock(lb)
    val elem = Val(fromSlot(emitT("call i64 @rt_list_get(ptr ${r.v}, i64 $i)"), t.elem), t.elem)
    scopes.add(Scope())
    val pl = declare(param, t.elem, false, false)
    emit("store ${t.elem.llvm} ${elem.v}, ptr ${pl.slot}")
    val v = genBlockBody(lam, true)
    scopes.removeAt(scopes.size - 1)
    when (n) {
        "map" -> { mapTy = v.t; val c = consume(v); emit("call void @rt_list_add(ptr $res, i64 ${toSlot(c)})") }
        "filter" -> {
            val add = label("lam.add")
            condBr(coerce(v, Ty.I1).v, add, ls)
            startBlock(add)
            if (t.elem.isRef) retain(elem.v, t.elem)
            emit("call void @rt_list_add(ptr $res, i64 ${toSlot(elem)})")
        }
        "forEach" -> releaseIfOwned(v)
        "sumOf" -> {
            if (!v.t.isNum) fail("sumOf selector must return a number")
            accTy = if (v.t == Ty.I8) Ty.I32 else v.t
            val cur = emitT("load ${accTy.llvm}, ptr $acc")
            val nv = arith(BinOp.PLUS, Val(cur, accTy), v)
            emit("store ${accTy.llvm} ${nv.v}, ptr $acc")
        }
        "count" -> {
            val inc = label("lam.inc")
            condBr(coerce(v, Ty.I1).v, inc, ls)
            startBlock(inc)
            emit("store i32 ${emitT("add i32 ${emitT("load i32, ptr $acc")}, 1")}, ptr $acc")
        }
    }
    startBlock(ls)
    emit("store i64 ${emitT("add i64 ${emitT("load i64, ptr $idx")}, 1")}, ptr $idx")
    br(lc)
    startBlock(le)
    val init = when (n) {
        "map" -> "  $res = call ptr @rt_list_new(ptr ${releaseFn(mapTy) ?: "null"})\n"
        "filter" -> "  $res = call ptr @rt_list_new(ptr ${releaseFn(t.elem) ?: "null"})\n"
        "sumOf", "count" -> {
            allocas.append("  $acc = alloca ${accTy.llvm}\n")
            "  store ${accTy.llvm} ${if (accTy.isFp) "0.0" else "0"}, ptr $acc\n"
        }
        else -> ""
    }
    body.insert(initPos, init)
    return when (n) {
        "map" -> { if (mapTy == Ty.Unit) fail("map lambda must return a value"); Val(res, Ty.ListTy(mapTy), true) }
        "filter" -> Val(res, t, true)
        "sumOf", "count" -> Val(emitT("load ${accTy.llvm}, ptr $acc"), accTy)
        else -> Val("", Ty.Unit)
    }
}

/** Element kind (I, L, C) of a range built from [a] and [b]. */
internal fun NativeBackend.rangeKind(a: Ty, b: Ty): String = when {
    a == Ty.Char && b == Ty.Char -> "C"
    a.isUnsigned || b.isUnsigned -> if (a == Ty.U64 || b == Ty.U64 || a == Ty.I64 || b == Ty.I64) "UL" else "U"
    a == Ty.I64 || b == Ty.I64 -> "L"
    a.isInt && b.isInt -> "I"
    else -> fail("cannot build a range of $a and $b")
}

internal fun NativeBackend.progKind(t: Ty): String? = (t as? Ty.Cls)?.let { PROGRESSIONS[it.name] }

/** `a..b`, `a until b`, `a downTo b`, `p step n` as values: progression objects from the prelude. */
internal fun NativeBackend.rangeValue(e: BinE): Val {
    val a = genExpr(e.l)
    // `C(0)..C(2)` on a class: its `operator fun rangeTo` (member or extension), not a prelude progression.
    if (e.op == BinOp.RANGE && (a.t is Ty.Cls || a.t is Ty.Iface) && progKind(a.t) == null) return method(a, "rangeTo", opCall("rangeTo", e.r))
    val b = genExpr(e.r)
    val k = if (e.op == BinOp.STEP) progKind(a.t) ?: fail("step on ${a.t}") else rangeKind(a.t, b.t)
    val fn = "kt_" + when (e.op) { BinOp.RANGE -> "rangeTo"; BinOp.UNTIL -> "until"; BinOp.DOWN_TO -> "downTo"; BinOp.STEP -> "step"; else -> error("internal: range operator ${e.op}") } + k
    return withTemps(listOf(a, b)) { (x, y) -> genNamedCall(fn, CallE(NameE(fn), emptyList(), listOf(x, y), null)) }
}

/** `x in r`: range literals compare against their bounds directly; other values call `contains`. */
internal fun NativeBackend.inRange(xe: E, re: E): Val {
    if (re is BinE && re.op in setOf(BinOp.RANGE, BinOp.UNTIL, BinOp.DOWN_TO)) {
        val x = genExpr(xe); val lo = genExpr(re.l); val hi = genExpr(re.r)
        val k = rangeKind(lo.t, hi.t)
        val t0 = when (k) { "C" -> Ty.I32; "L" -> Ty.I64; "U" -> Ty.U32; "UL" -> Ty.U64; else -> if (x.t == Ty.I64) Ty.I64 else Ty.I32 }
        // A wider value is compared in its own type (`x: ULong in UInt.MIN_VALUE..UInt.MAX_VALUE`).
        val t = if (k != "C" && x.t.isInt && x.t.rank > t0.rank && x.t.isUnsigned == t0.isUnsigned) x.t else t0
        val c = { v: Val -> if (v.t == Ty.Char) Val(v.v, Ty.I32) else coerce(v, t) }
        if (k == "C" && x.t != Ty.Char) fail("type mismatch: expected Char, found ${x.t}")
        val xv = c(x); val l = c(lo); val h = c(hi)
        val (p, q) = when (re.op) {
            BinOp.RANGE -> compare(BinOp.LE, l, xv) to compare(BinOp.LE, xv, h)
            BinOp.UNTIL -> compare(BinOp.LE, l, xv) to compare(BinOp.LT, xv, h)
            else -> compare(BinOp.LE, h, xv) to compare(BinOp.LE, xv, l)
        }
        return Val(emitT("and i1 ${p.v}, ${q.v}"), Ty.I1)
    }
    val r = genExpr(re)
    return withTemps(listOf(r)) { (rv) -> genExpr(CallE(MemberE(rv, "contains", false, line = line), emptyList(), listOf(xe), null, line = line)) }
}

internal fun NativeBackend.charOp(op: BinOp, a: Val, b: Val): Val {
    val asInt = { v: Val -> if (v.t == Ty.Char) Val(v.v, Ty.I32) else v }
    return when {
        op in setOf(BinOp.EQ, BinOp.NE, BinOp.LT, BinOp.GT, BinOp.LE, BinOp.GE) && a.t == Ty.Char && b.t == Ty.Char -> compare(op, asInt(a), asInt(b))
        op == BinOp.MINUS && a.t == Ty.Char && b.t == Ty.Char -> arith(BinOp.MINUS, asInt(a), asInt(b))
        // Char arithmetic wraps modulo 2^16.
        (op == BinOp.PLUS || op == BinOp.MINUS) && a.t == Ty.Char && b.t.isInt -> Val(emitT("and i32 ${arith(op, asInt(a), coerce(b, Ty.I32)).v}, 65535"), Ty.Char)
        op == BinOp.PLUS && a.t == Ty.Str -> concat(a, b)
        else -> fail("operator $op on ${a.t} and ${b.t}")
    }
}

internal fun NativeBackend.concat(a: Val, b: Val): Val {
    val sb = emitT("call ptr @rt_sb_new()")
    appendVal("rt_sb_", "ptr $sb, ", a); appendVal("rt_sb_", "ptr $sb, ", b)
    return Val(emitT("call ptr @rt_sb_done(ptr $sb)"), Ty.Str, true)
}
