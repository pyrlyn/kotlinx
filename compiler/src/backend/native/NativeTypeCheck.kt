package newkotlin

// Type tests and casts: `is`/`as`/`as?`, smart casts, runtime class names and class literals.
// Members of [NativeBackend], split out by concern (extension functions on the backend instance).

/** Classes that are [cls] or derive from it. */
internal fun NativeBackend.subclassesOf(cls: String): List<String> = classes.values.filter { it.name == cls || cls in it.ancestors }.map { it.name }.sorted()

/** Dynamic type test of a non-null object pointer against class [cls] (compares vtables). */
internal fun NativeBackend.isInstance(p: String, cls: String): String {
    val subs = subclassesOf(cls)
    if (subs.isEmpty()) return "false"
    val vt = emitT("load ptr, ptr ${emitT("getelementptr i8, ptr $p, i64 8")}")
    var r: String? = null
    for (x in subs) { val c = emitT("icmp eq ptr $vt, @vt.$x"); r = if (r == null) c else emitT("or i1 $r, $c") }
    return r!!
}

/** i1 for `v is t` (does not consume [v]). */
internal fun NativeBackend.isCheck(v: Val, t: TypeRef): String {
    // A nullable primitive is null or an AnyBox: checked like an `Any?`.
    if (v.t is Ty.NBox) return isCheck(Val(v.v, Ty.Iface("Any"), false), t)
    if (Regex("Function\\d+").matches(t.name)) fail("type checks against ${t.name} are not supported")
    val star = t.args.any { it.name == "*" }
    val target = if (star || t.name == "Number") null else resolveOrNull(t)
    if (v.t.llvm != "ptr") return if (target == v.t || (target == null && t.name == "Any")) "true" else "false"
    if (v.v == "null") return if (t.nullable) "true" else "false"
    val nonNull = if (t.nullable) "true" else emitT("icmp ne ptr ${v.v}, null")
    if (t.name == "Any") return nonNull
    if (target != null && statically(v.t, target)) return nonNull
    if (!(v.t is Ty.Cls || v.t is Ty.Iface)) return if (target == v.t) nonNull else "false"
    if (isAny(v.t) && target is Ty.Iface && target.name.substringBefore('$') == "Comparable") fail("Any as Comparable is not supported")
    if (isAny(v.t) && target != null && target !is Ty.Cls && target !is Ty.Iface && anyBoxName(target) == null) fail("type check of Any against $target is not supported")
    val boxed = if (isAny(v.t)) target?.let { anyBoxName(it) } else null
    val special = if (isAny(v.t) && t.name in LISTY && (star || target is Ty.ListTy)) (if (target is Ty.ListTy) listOfNotNull(anyBoxName(target)?.let { "AnyBox$it" }) else (listOf("") + ANY_BOXED).map { "AnyBoxList$it" })
    else if (target == null && !star) when (t.name) {
        "Number" -> if (isAny(v.t)) listOf("Int", "Long", "Double", "Float", "Byte", "UInt", "ULong").map { "AnyBox$it" } else emptyList()
        "CharSequence" -> if (isAny(v.t)) listOf("AnyBoxString") else emptyList()
        else -> fail("unknown type ${t.name}")
    } else null
    val subs = if (special != null) special else if (boxed != null) listOf("AnyBox$boxed") else {
        val name = (target as? Ty.Cls)?.name ?: (target as? Ty.Iface)?.name ?: if (star) t.name else return "false"
        instanceClasses(name, star)
    }
    if (subs.isEmpty()) return if (t.nullable) emitT("icmp eq ptr ${v.v}, null") else "false"
    val from = cur
    val lc = label("is.chk"); val le = label("is.end")
    val isNull = emitT("icmp eq ptr ${v.v}, null")
    condBr(isNull, le, lc)
    startBlock(lc)
    val vt = emitT("load ptr, ptr ${emitT("getelementptr i8, ptr ${v.v}, i64 8")}")
    var r: String? = null
    for (x in subs) { val c = emitT("icmp eq ptr $vt, @vt.$x"); r = if (r == null) c else emitT("or i1 $r, $c") }
    val fromC = cur
    br(le); startBlock(le)
    return emitT("phi i1 [ ${t.nullable}, %$from ], [ $r, %$fromC ]")
}

/** True if [e] is a parameter of the current generic instance typed by a type parameter whose bound is not
 *  `Double`/`Double?` (e.g. `Any`): `==` on it is boxed `equals`, which compares Doubles by bits (0.0 != -0.0). */
internal fun NativeBackend.boxedDoubleOperand(e: E): Boolean {
    val f = instFun ?: return false
    if (e !is NameE) return false
    val p = f.params.firstOrNull { it.name == e.n } ?: return false
    if (p.type.name !in tenv) return false
    val bound = TypeParamBounds.bounds["$instLine:${p.type.name}"]
    return bound == null || bound.name !in setOf("Double", "Float")
}

internal fun NativeBackend.fpToInt(r: Val, target: Ty): Val {
    val w = if (target == Ty.I8) Ty.I32 else target
    val fn = "llvm.${if (w.isUnsigned) "fptoui" else "fptosi"}.sat.${w.llvm}.${if (r.t == Ty.F32) "f32" else "f64"}"
    val v = Val(emitT("call ${w.llvm} @$fn(${r.t.llvm} ${r.v})"), w)
    return if (target == Ty.I8) Val(emitT("trunc i32 ${v.v} to i8"), Ty.I8) else v
}

internal fun NativeBackend.doubleBits(v0: Val): String {
    val v = coerce(v0, Ty.F64)
    val b = emitT("bitcast double ${v.v} to i64")
    val nan = emitT("fcmp uno double ${v.v}, ${v.v}")
    return emitT("select i1 $nan, i64 9221120237041090560, i64 $b")
}

internal fun NativeBackend.genAs(e0: AsE): Val {
    // `x as T` with an unbounded type parameter (implicit bound `Any?`) is unchecked: a null passes through.
    val e = if (instLine >= 0 && e0.type.name in tenv && !e0.type.nullable && !e0.safe && "$instLine:${e0.type.name}" in TypeParamBounds.unbounded)
        AsE(e0.e, TypeRef(e0.type.name, e0.type.args, true), false, line = e0.line) else e0
    // `x as T` with a class-bounded type parameter (`T : C`) is unchecked beyond the bound, as with erasure:
    // check against the bound, then view the value as the instance's type.
    if (instLine >= 0 && e0.type.name in tenv && !e0.type.nullable && !e0.safe && e0.type.args.isEmpty()) {
        val b = TypeParamBounds.bounds["$instLine:${e0.type.name}"]
        val tt = tenv.getValue(e0.type.name)
        if (b != null && b.args.isEmpty() && !b.nullable && b.name in classes && tt is Ty.Cls && tt.name != b.name) {
            val r = genAs(AsE(e0.e, b, false, line = e0.line))
            return Val(r.v, tt, r.owned)
        }
    }
    val v = genExpr(e.e)
    if (e.type.name == "Any" && "Any" in ifaces && (anyBoxName(v.t) != null || v.t is Ty.Iface || v.t is Ty.Cls && v.t.name.substringBefore('$') !in genericClasses))
        // `x as Any` of a reference that may be null at run time (an unchecked `T` result): NullPointerException.
        return coerce(if (!e.type.nullable && !e.safe && v.t.llvm == "ptr" && v.v != "null") genNonNull(v) else v, Ty.Iface("Any"))
    if (e.type.name == "Number" && !isAny(v.t) && !v.t.isNum) fail("cast to Number is not supported")
    val star = e.type.args.any { it.name == "*" }
    val target = if (e.type.name == "Any") v.t else if (star) (v.t.takeIf { v.t is Ty.Cls || v.t is Ty.Iface } ?: fail("unsupported cast to ${e.type.name}<*>")) else resolve(TypeRef(e.type.name, e.type.args, false))
    // `c as Continuation<Any>` with `c: Continuation<IC>`: unchecked (erased) between instances of one generic
    // interface whose type arguments are all references (same layout of the objects behind them).
    if (v.t is Ty.Iface && target is Ty.Iface && v.t != target && !e0.safe) {
        val oa = instOrigin[v.t.name]; val ob = instOrigin[target.name]
        val prim = setOf("Int", "Long", "Double", "Float", "Boolean", "Char", "Short", "Byte", "Unit", "UInt", "ULong", "UByte", "UShort")
        // Continuations convert their values (`Any` holds boxed primitives and strings): an adapter.
        if (oa != null && ob != null && oa.first == "Continuation" && ob.first == "Continuation" && "KcCastCont" in genericClasses) {
            val tmp = "\$ccast${suspConvN++}"
            val l = declare(tmp, v.t, false, true)
            emit("store ptr ${consume(v).v}, ptr ${l.slot}")
            return genExpr(CallE(NameE("KcCastCont", line = line), listOf(oa.second[0], ob.second[0]), listOf(NameE(tmp, line = line)), null, line = line)).let { r -> Val(r.v, target, r.owned) }
        }
        if (oa != null && ob != null && oa.first == ob.first && (oa.second + ob.second).none { it.name in prim && !it.nullable }) return Val(v.v, target, v.owned)
    }
    // Instances of one generic class with different type arguments are distinct monomorphized classes.
    if (v.t is Ty.Cls && target is Ty.Cls && v.t != target && '$' in v.t.name && v.t.name.substringBefore('$') == target.name.substringBefore('$'))
        fail("unchecked cast between instances of generic class ${e.type.name} is not supported")
    // `xs as Array<Any?>` with `xs: Array<Int>` (an unchecked erasing cast that Kotlin code relies on): list
    // elements are stored in their own representation (an Int unboxed, an Any? boxed), so the list cannot be
    // reinterpreted and the runtime check would always fail. Other list casts keep that check, which only fails
    // when it runs.
    if (v.t is Ty.ListTy && target is Ty.ListTy && isAny(target.elem) && !statically(v.t, target))
        fail("unchecked cast from ${v.t} to $target is not supported")
    // `null as Int` (also `null as T` in an instance with T = Int): always a NullPointerException, as in Kotlin.
    if (v.v == "null" && target.llvm != "ptr") {
        if (excMode) throwNew("NullPointerException", Val("null", Ty.Str))
        else { emit("call void @rt_panic(ptr ${cstr("NullPointerException")})"); emit("unreachable"); term = true }
        return Val("undef", Ty.Nothing)
    }
    // `x as Int` with `x: Int?` (also `v as T` in an instance with T = Int): null check, then unbox.
    if (v.t is Ty.NBox && target.llvm != "ptr" && !e0.type.nullable && !e0.safe) {
        if (v.t.inner == target) return genNonNull(v)
        fail("unsupported cast from ${v.t} to $target")
    }
    if (v.t.llvm != "ptr") {
        if (v.t == target) return v
        if ((v.t == Ty.Unit || v.t == Ty.UnitV) && (target == Ty.Unit || target == Ty.UnitV)) return coerce(v, target)
        fail("unsupported cast from ${v.t} to $target")
    }
    if (v.v == "null") {
        if (e.type.nullable || e.safe) return Val("null", target)
    }
    if (statically(v.t, target) && (e.type.nullable || e.safe) && v.v != "null") return Val(v.v, target, v.owned)
    val unboxing = isAny(v.t) && anyBoxName(target) != null
    // `a as T` (T unbounded, so nullable) unboxing from Any in an instance where T is a primitive or String:
    // a null passes through for String; otherwise checked like a non-null cast.
    if (unboxing && !e.safe && e.type.nullable && e !== e0) {
        if (target == Ty.Str) {
            val ln = label("asn.null"); val lv = label("asn.val"); val le = label("asn.end")
            condBr(emitT("icmp eq ptr ${v.v}, null"), ln, lv)
            startBlock(ln); br(le)
            startBlock(lv)
            val r = genAsChecked(v, e0.type, target)
            val vFrom = cur; br(le)
            startBlock(le)
            return Val(emitT("phi ptr [ null, %$ln ], [ ${r.v}, %$vFrom ]"), target, r.owned)
        }
        return genAsChecked(v, e0.type, target)
    }
    if (unboxing && (e.safe || e.type.nullable)) fail("nullable cast from Any to $target is not supported")
    // A safe cast of a reference to a primitive type would need a nullable primitive result (was invalid IR).
    if (e.safe && target.llvm != "ptr") fail("safe cast from ${v.t} to $target is not supported")
    if (!e.safe) return genAsChecked(v, e.type, target)
    val ok = isCheck(v, e.type)
    val lok = label("as.ok"); val lbad = label("as.bad")
    if (e.safe) {
        val from = cur
        condBr(ok, lok, lbad)
        startBlock(lbad)
        releaseIfOwned(v)
        val fromB = cur
        br(lok); startBlock(lok)
        return Val(emitT("phi ptr [ ${v.v}, %$from ], [ null, %$fromB ]"), target, v.owned)
    }
    error("unreachable")
}

/** Checked cast of reference [v] to [type] (resolved [target]): ClassCastException on mismatch, unboxing from Any. */
internal fun NativeBackend.genAsChecked(v: Val, type: TypeRef, target: Ty): Val {
    val unboxing = isAny(v.t) && anyBoxName(target) != null
    val ok = isCheck(v, type)
    val lok = label("as.ok"); val lbad = label("as.bad")
    condBr(ok, lok, lbad)
    startBlock(lbad)
    // A null reaching a non-null cast (an unchecked `T` holding null) is a NullPointerException, as in Kotlin.
    if (excMode && !type.nullable) {
        val lnull = label("as.npe"); val lcce = label("as.cce")
        condBr(emitT("icmp eq ptr ${v.v}, null"), lnull, lcce)
        startBlock(lnull); throwNew("NullPointerException", Val("null", Ty.Str))
        startBlock(lcce)
    }
    releaseIfOwned(v)
    val msg = "class ${v.t} cannot be cast to class ${type.name}"
    if (excMode) throwNew("ClassCastException", Val(cstr(msg), Ty.Str))
    else { emit("call void @rt_panic(ptr ${cstr("ClassCastException: $msg")})"); emit("unreachable"); term = true }
    startBlock(lok)
    // A cast between different representations (`it as Int` with `it: String`) always fails: the ok branch is
    // dead, its value only has to have the target's IR type.
    return if (unboxing) unbox(v, target) else if (v.t.llvm != target.llvm) Val("undef", target) else Val(v.v, target, v.owned)
}

/** Smart casts implied by [cond] being true (positive) or false: local name -> narrowed type. */
internal fun NativeBackend.smartCasts(cond: E, positive: Boolean): List<Pair<String, TypeRef>> = when {
    cond is IsE && cond.e is NameE && cond.neg != positive && cond.type.args.none { it.name == "*" } && cond.type.name != "Any" -> listOf(cond.e.n to cond.type)
    // `x != null` narrows a nullable primitive local to its value.
    cond is BinE && ((cond.op == BinOp.NE && positive) || (cond.op == BinOp.EQ && !positive)) && cond.l is NameE && cond.r is NullL -> listOf(cond.l.n to TypeRef("\$nn", emptyList(), false))
    cond is BinE && cond.op == BinOp.AND && positive -> smartCasts(cond.l, true) + smartCasts(cond.r, true)
    cond is BinE && cond.op == BinOp.OR && !positive -> smartCasts(cond.l, false) + smartCasts(cond.r, false)
    cond is UnE && cond.op == UnOp.NOT -> smartCasts(cond.e, !positive)
    else -> emptyList()
}

internal fun NativeBackend.nullableAlias(e: E) = e is NameE && lookup(e.n)?.slot?.let { it in nullableAliases } == true

/** Declares borrowed narrowed aliases of the [casts] locals in the current scope (skipping reassigned vars). */
internal fun NativeBackend.applyCasts(casts: List<Pair<String, TypeRef>>) {
    for ((n, tr) in casts) {
        val l = lookup(n) ?: continue
        if (l.isVar || l.t.llvm != "ptr") continue
        // `a is Float` on a `Float?` local: the unboxed value.
        if (l.t is Ty.NBox && !tr.nullable && resolveOrNull(tr) == l.t.inner) {
            val x = unbox(Val(emitT("load ptr, ptr ${l.slot}"), l.t), l.t.inner)
            val nl = declare(n, l.t.inner, false, false)
            emit("store ${x.t.llvm} ${x.v}, ptr ${nl.slot}")
            continue
        }
        if (tr.name == "\$nn") {
            if (l.t is Ty.NBox) {
                val x = unbox(Val(emitT("load ptr, ptr ${l.slot}"), l.t), l.t.inner)
                val nl = declare(n, l.t.inner, false, false)
                emit("store ${x.t.llvm} ${x.v}, ptr ${nl.slot}")
            }
            continue
        }
        val t = resolveOrNull(tr) ?: continue
        // `x is Int?`: still possibly null, so the alias is a nullable primitive (same box pointer).
        if (isAny(l.t) && tr.nullable && canBox(t)) { scopes.last().vars[n] = Local(l.slot, Ty.NBox(t), false); continue }
        if (isAny(l.t) && anyBoxName(t) != null) {
            // Smart cast of an Any to a primitive or String: a borrowed unboxed alias.
            val x = unbox(Val(emitT("load ptr, ptr ${l.slot}"), l.t), t)
            val nl = declare(n, t, false, false)
            emit("store ${t.llvm} ${x.v}, ptr ${nl.slot}")
            if (tr.nullable) nullableAliases.add(nl.slot)
            continue
        }
        if (t == l.t || !(t is Ty.Cls || t is Ty.Iface)) continue
        scopes.last().vars[n] = Local(l.slot, t, false)
    }
}

/** Simple name of a value's static type when that is also its runtime class (primitives, strings, final classes). */
internal fun NativeBackend.runtimeClassName(t: Ty): String? = when (t) {
    Ty.I32 -> "Int"; Ty.I64 -> "Long"; Ty.F64 -> "Double"; Ty.F32 -> "Float"; Ty.I1 -> "Boolean"; Ty.Char -> "Char"; Ty.I8 -> "Byte"
    Ty.U32 -> "UInt"; Ty.U64 -> "ULong"; Ty.Str -> "String"
    is Ty.Cls -> t.name.takeIf { n -> '$' !in n && classes.values.none { n in it.ancestors } && (n in classDecls || n in PRELUDE_EXCEPTIONS) }
    else -> null
}

/**
 * `T::class` / `e::class`: a `KClass(simpleName, qualifiedName)`. A type name is resolved statically (type parameters
 * are concrete after monomorphization); for an expression (evaluated for its side effects) the static type must be
 * its runtime class. User classes have no package: their qualified name is the simple name.
 */
internal fun NativeBackend.classLiteral(x: E): Val {
    val tn = (x as? NameE)?.n?.takeIf { lookup(it) == null && it != "this" && owner?.let { o -> o.index(it) >= 0 } != true }
    val n: String = if (tn != null && tn in STD_KCLASS && tn !in classDecls) tn else if (tn != null) {
        val t = try { resolve(TypeRef(tn, emptyList(), false)) } catch (er: Exception) { fail("class literal of unknown type $tn is not supported") }
        if (tn in STD_KCLASS) tn else runtimeClassName(t) ?: fail("class literal of $t is not supported")
    } else {
        val v = genExpr(x)
        releaseIfOwned(v)
        runtimeClassName(v.t) ?: fail("class literal of a ${v.t} value is not supported (runtime class unknown)")
    }
    if (n !in STD_KCLASS && n !in classDecls) fail("class literal of $n is not supported")
    val q = STD_KCLASS[n] ?: n
    fun str(s: String) = strL(s).also { it.line = line }
    return genExpr(CallE(NameE("KClass", line = line), emptyList(), listOf(str(n), str(q)), null, line = line))
}
