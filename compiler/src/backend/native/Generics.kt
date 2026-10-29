package newkotlin

/**
 * Generic classes are monomorphized: `class Box<T>` is never compiled itself; every type-argument list used by the
 * program (`Box<Int>`, `Box<String>`) becomes a plain class `Box$Int` / `Box$String` with T substituted in its AST.
 * Instances are discovered during code generation ([NeedInstance]); the class is added and generation restarts,
 * until the instance set is closed. Each instance has exact field types, so primitives stay unboxed and the
 * Perceus RC / cycle analysis (cyclic classes, field release functions) is computed per instance.
 */
fun compileGenerics(p00: Program): String {
    val p0 = boxCapturedVars(rewriteStatics(p00))
    val generic = p0.classes.filter { it.typeParams.isNotEmpty() }.associateBy { it.name }
    val inst = LinkedHashMap<String, ClassD>()
    val extraClasses = LinkedHashMap<String, ClassD>()
    val extraIfaces = LinkedHashMap<String, InterfaceD>()
    val origin = HashMap<String, Pair<String, List<TypeRef>>>()
    val genericIfaces = p0.ifaces.filter { it.typeParams.isNotEmpty() }.associateBy { it.name }
    val ifaceInst = LinkedHashMap<String, InterfaceD>()
    /** Generic supertype `I<Int>` -> its instance name, instantiating it if needed. */
    fun ensure(n: String, args0: List<TypeRef>): String {
        val args = args0.map { canon(it) }
        val m = mangle(n, args)
        generic[n]?.let { cd -> if (m !in inst) { origin[m] = n to args; inst[m] = Subst(cd.typeParams.zip(args).toMap()).cls(cd, m) } }
        genericIfaces[n]?.let { d -> if (m !in ifaceInst) { origin[m] = n to args; ifaceInst[m] = Subst(d.typeParams.zip(args).toMap()).iface(d, m) } }
        return m
    }
    fun fixSupers(c: ClassD): ClassD = if (c.superTypeArgs.isEmpty()) c else
        ClassD(c.name, c.fields, c.supers.map { s -> c.superTypeArgs[s]?.let { ensure(s, it) } ?: s }, c.methods, c.line, c.ctorParams,
            c.inits, c.superArgs, c.typeParams, c.enumEntries, c.isValue)
    while (true) {
        var cls = (p0.classes.filter { it.typeParams.isEmpty() } + extraClasses.values).map { fixSupers(it) }
        while (true) {
            val before = inst.size
            val fixed = inst.values.toList().map { fixSupers(it) }
            fixed.forEach { inst[it.name] = it }
            if (inst.size == before) break
        }
        cls = cls + inst.values
        val prog = Program(p0.funs, cls, p0.ifaces.filter { it.typeParams.isEmpty() } + ifaceInst.values + extraIfaces.values, p0.globals)
        try {
            return NativeBackend(prog, generic, origin, genericIfaces).generate()
        } catch (n: NeedClass) {
            if (n.cd.name in extraClasses) throw CompileError("internal: class ${n.cd.name} requested twice")
            if (extraClasses.size >= 5000) throw CompileError("too many lambda classes")
            extraClasses[n.cd.name] = n.cd
        } catch (n: NeedIface) {
            if (n.d.name in extraIfaces) throw CompileError("internal: interface ${n.d.name} requested twice")
            extraIfaces[n.d.name] = n.d
        } catch (n: NeedInstance) {
            val m = mangle(n.name, n.args)
            if (n.name in genericIfaces) {
                if (m in ifaceInst) throw CompileError("internal: interface instance $m requested twice")
                ensure(n.name, n.args); continue
            }
            if (m in inst) throw CompileError("internal: instance $m requested twice")
            if (inst.size >= 1000) throw CompileError("too many generic class instances (recursive generic type?)")
            val cd = generic.getValue(n.name)
            origin[m] = n.name to n.args
            inst[m] = Subst(cd.typeParams.zip(n.args).toMap()).cls(cd, m)
        }
    }
}

/**
 * Local `var`s assigned inside a lambda are boxed: `var x = e` -> `val x = $Ref(e)`, uses `x` -> `x.v`,
 * so closures and the enclosing function share one mutable cell (a generic class `$Ref<T>`).
 */
private val INLINE_LAMBDAS = setOf("map", "filter", "forEach", "sumOf", "count", "usePinned")

private fun boxCapturedVars(p: Program): Program {
    var used = false
    /** Lambda nesting depth of each `var` declaration (min) and of assignments to each name (max). */
    fun names(b: Block, out: MutableMap<String, Int>, decl: MutableMap<String, Int>) {
        val w = object {
            fun asg(t: E, d: Int) { if (t is NameE) out[t.n] = maxOf(out[t.n] ?: -1, d) }
            fun e(x: E, d: Int) {
                when (x) {
                    is PostIncE -> { asg(x.target, d); e(x.target, d) }
                    is StrL -> x.parts.forEach { if (it is E) e(it, d) }
                    is CallE -> { e(x.callee, d); x.args.forEach { e(it, d) }; x.lambda?.let { blk(it, if (x.callee is MemberE && x.callee.n in INLINE_LAMBDAS) d else d + 1) } }
                    is MemberE -> e(x.recv, d)
                    is IndexE -> { e(x.recv, d); e(x.idx, d) }
                    is BinE -> { e(x.l, d); e(x.r, d) }
                    is UnE -> e(x.e, d)
                    is NamedE -> e(x.e, d)
                    is IsE -> e(x.e, d)
                    is AsE -> e(x.e, d)
                    is IfE -> { e(x.cond, d); blk(x.th, d); x.el?.let { blk(it, d) } }
                    is WhenE -> { x.subj?.let { e(it, d) }; x.branches.forEach { (c, bl) -> c?.forEach { e(it, d) }; blk(bl, d) } }
                    is LambdaE -> blk(x.body, d + 1)
                    is TryE -> { blk(x.body, d); x.catches.forEach { blk(it.body, d) }; x.fin?.let { blk(it, d) } }
                    else -> {}
                }
            }
            fun st(x: S, d: Int) {
                when (x) {
                    is VarS -> { if (x.isVar) decl[x.name] = minOf(decl[x.name] ?: Int.MAX_VALUE, d); e(x.init, d) }
                    is AssignS -> { asg(x.target, d); e(x.target, d); e(x.value, d) }
                    is IncS -> { asg(x.target, d); e(x.target, d) }
                    is WhileS -> { e(x.cond, d); blk(x.body, d) }
                    is ForS -> { e(x.iter, d); blk(x.body, d) }
                    is RetS -> x.e?.let { e(it, d) }
                    is ExprS -> e(x.e, d)
                    is JumpS -> {}
                }
            }
            // Inline builtin lambdas (forEach, map, ...) are expanded in place, so they do not add depth.
            fun blk(bl: Block, d: Int) = bl.stmts.forEach { st(it, d) }
        }
        w.blk(b, 0)
    }
    fun box(body: Block): Block {
        val assigned = HashMap<String, Int>(); val decl = HashMap<String, Int>()
        names(body, assigned, decl)
        val boxed = decl.filter { (n, d) -> (assigned[n] ?: -1) > d }.keys
        if (boxed.isEmpty()) return body
        used = true
        lateinit var sub: Subst
        sub = Subst(emptyMap(), { x -> if (x is NameE && x.n in boxed) MemberE(NameE(x.n).also { it.line = x.line }, "v", false).also { it.line = x.line } else null },
            { x -> if (x is VarS && x.isVar && x.name in boxed)
                VarS(x.name, false, null, CallE(NameE("\$Ref"), listOfNotNull(x.type), listOf(sub.e(x.init)), null).also { it.line = x.line }).also { it.line = x.line }
                else null })
        return sub.block(body)
    }
    fun fn(f: FunD): FunD {
        val body = f.body ?: return f
        val nb = box(body)
        return if (nb === body) f else FunD(f.name, f.params, f.ret, nb, f.exprBody, f.line, f.typeParams)
    }
    val globals = p.globals.map { g ->
        val b = Block(listOf(ExprS(g.init)))
        val nb = box(b)
        if (nb === b) g else VarS(g.name, g.isVar, g.type, (nb.stmts.single() as ExprS).e).also { it.line = g.line }
    }
    val funs = p.funs.map { fn(it) }
    val classes = p.classes.map { c -> ClassD(c.name, c.fields, c.supers, c.methods.map { fn(it) }, c.line, c.ctorParams, c.inits, c.superArgs, c.typeParams, c.enumEntries, c.isValue, c.superTypeArgs) }
    if (!used) return p
    val ref = ClassD("\$Ref", listOf(FieldD("v", TypeRef("T", emptyList(), false), true)), emptyList(), emptyList(), 0, typeParams = listOf("T"))
    return Program(funs, classes + ref, p.ifaces, globals, p.companions)
}

/** `Box` + [Int, ArrayList<String>] -> `Box$Int$ArrayList$String$$`: a valid LLVM identifier, unique per argument list. */
fun mangle(n: String, args: List<TypeRef>): String =
    n + args.joinToString("") { "$" + it.toString().replace("<", "$").replace(">", "$$").replace(",", "$").replace("?", "_N") }

/** Canonical spelling of a concrete type argument (`List<Int>` -> `ArrayList<Int>`), matching [tyRef]. */
fun canon(t: TypeRef): TypeRef = TypeRef(when (t.name) { "List", "MutableList" -> "ArrayList"; "Map", "MutableMap" -> "HashMap"; else -> t.name },
    t.args.map { canon(it) }, false)

/** Concrete type back to a type reference (for type arguments inferred from values). */
fun tyRef(t: Ty): TypeRef = when (t) {
    is Ty.ListTy -> TypeRef("ArrayList", listOf(tyRef(t.elem)), false)
    is Ty.MapTy -> TypeRef("HashMap", listOf(tyRef(t.k), tyRef(t.v)), false)
    else -> TypeRef(t.toString(), emptyList(), false)
}

/**
 * Static references: `E.A` -> the enum entry global `E$A`; `E.values()` / `E.entries` -> `E$values`;
 * `C.x` / `C.Companion.x` -> `C$Companion.x` (companion object members). Inside an enum body its entries, and inside
 * a class its companion's members, are in scope unqualified.
 */
private fun rewriteStatics(p: Program): Program {
    val enums = p.classes.filter { it.enumEntries != null }.associate { c -> c.name to c.enumEntries!!.map { it.first }.toSet() }
    val comps = p.companions
    if (enums.isEmpty() && comps.isEmpty()) return p
    val global: (E) -> E? = { x ->
        when {
            x is MemberE && x.recv is NameE && enums[x.recv.n]?.contains(x.n) == true -> NameE("${x.recv.n}$${x.n}")
            x is MemberE && x.recv is NameE && x.recv.n in enums && x.n == "entries" -> NameE("${x.recv.n}\$values")
            x is CallE && x.args.isEmpty() && x.callee is MemberE && x.callee.recv is NameE && x.callee.recv.n in enums && x.callee.n == "values" ->
                NameE("${x.callee.recv.n}\$values")
            x is MemberE && x.recv is MemberE && x.recv.recv is NameE && comps[x.recv.recv.n]?.let { it.first == "${x.recv.recv.n}$${x.recv.n}" } == true ->
                MemberE(NameE(comps.getValue(x.recv.recv.n).first), x.n, x.safe)
            x is MemberE && x.recv is NameE && comps[x.recv.n]?.second?.contains(x.n) == true ->
                if (comps.getValue(x.recv.n).first == "${x.recv.n}$${x.n}") NameE(comps.getValue(x.recv.n).first)
                else MemberE(NameE(comps.getValue(x.recv.n).first), x.n, x.safe)
            else -> null
        }?.also { it.line = x.line }
    }
    val sub = Subst(emptyMap(), global)
    val classes = p.classes.map { c ->
        val own = enums[c.name].orEmpty()
        val comp = comps[c.name]
        val mine = c.fields.map { it.name }.toSet() + c.methods.map { it.name } + c.ctorParams.map { it.name }
        if (own.isEmpty() && comp == null) return@map sub.cls(c, c.name, keepTypeParams = true)
        Subst(emptyMap(), rw = { x ->
            when {
                x is NameE && x.n in own -> NameE("${c.name}$${x.n}").also { it.line = x.line }
                x is NameE && comp != null && x.n in comp.second && x.n !in mine && comp.first != "${c.name}$${x.n}" ->
                    MemberE(NameE(comp.first), x.n, false).also { it.line = x.line }
                else -> global(x)
            }
        }).cls(c, c.name, keepTypeParams = true)
    }
    return Program(p.funs.map { sub.fn(it) }, classes, p.ifaces, p.globals.map { sub.s(it) as VarS })
}

/** Substitutes type parameters by concrete type references throughout a class declaration ([rw]: optional expression rewrite). */
class Subst(val env: Map<String, TypeRef>, val rw: ((E) -> E?)? = null, val srw: ((S) -> S?)? = null) {
    val fieldMemo = HashMap<FieldD, FieldD>()

    fun ty(t: TypeRef): TypeRef = env[t.name]?.let { if (t.nullable) TypeRef(it.name, it.args, true) else it }
        ?: TypeRef(t.name, t.args.map { ty(it) }, t.nullable)

    fun cls(c: ClassD, name: String, keepTypeParams: Boolean = false): ClassD {
        val fields = c.fields.map { field(it) }
        return ClassD(name, fields, c.supers, c.methods.map { fn(it) }, c.line, c.ctorParams.map { Param(it.name, ty(it.type)) },
            c.inits.map { if (it is FieldD) field(it) else block(it as Block) }, c.superArgs?.map { e(it) },
            if (keepTypeParams) c.typeParams else emptyList(), c.enumEntries?.map { (n, a) -> n to a.map { e(it) } }, c.isValue,
            c.superTypeArgs.mapValues { (_, a) -> a.map { ty(it) } })
    }

    fun iface(d: InterfaceD, name: String): InterfaceD = InterfaceD(name, d.methods.map { fn(it) }, d.line)

    fun field(f: FieldD): FieldD = fieldMemo.getOrPut(f) { FieldD(f.name, f.type?.let { ty(it) }, f.isVar, f.init?.let { e(it) }) }

    fun fn(f: FunD): FunD = FunD(f.name, f.params.map { Param(it.name, ty(it.type)) }, f.ret?.let { ty(it) },
        f.body?.let { block(it) }, f.exprBody?.let { e(it) }, f.line, f.typeParams)

    fun block(b: Block): Block = Block(b.stmts.map { s(it) }, b.params, b.paramTypes.map { it?.let { t -> ty(t) } })
    fun blockN(b: Block?): Block? = b?.let { block(it) }

    fun s(x: S): S = srw?.invoke(x) ?: when (x) {
        is VarS -> VarS(x.name, x.isVar, x.type?.let { ty(it) }, e(x.init))
        is AssignS -> AssignS(e(x.target), x.op, e(x.value))
        is IncS -> IncS(e(x.target), x.op)
        is WhileS -> WhileS(e(x.cond), block(x.body), x.isDo)
        is ForS -> ForS(x.name, e(x.iter), block(x.body))
        is RetS -> RetS(x.e?.let { e(it) })
        is ExprS -> ExprS(e(x.e))
        is JumpS -> JumpS(x.isBreak)
    }.also { it.line = x.line }

    fun e(x: E): E = rw?.invoke(x) ?: when (x) {
        is IntL, is CharL, is DblL, is BoolL, is NullL, is NameE -> x
        is PostIncE -> PostIncE(e(x.target), x.op)
        is StrL -> StrL(x.parts.map { if (it is E) e(it) else it })
        is CallE -> CallE(e(x.callee), x.typeArgs.map { ty(it) }, x.args.map { e(it) }, blockN(x.lambda))
        is MemberE -> MemberE(e(x.recv), x.n, x.safe)
        is IndexE -> IndexE(e(x.recv), e(x.idx))
        is BinE -> BinE(x.op, e(x.l), e(x.r))
        is UnE -> UnE(x.op, e(x.e))
        is NamedE -> NamedE(x.name, e(x.e))
        is IsE -> IsE(e(x.e), ty(x.type), x.neg)
        is AsE -> AsE(e(x.e), ty(x.type), x.safe)
        is IfE -> IfE(e(x.cond), block(x.th), blockN(x.el))
        is LambdaE -> LambdaE(block(x.body))
        is TryE -> TryE(block(x.body), x.catches.map { Catch(it.name, ty(it.type), block(it.body)) }, blockN(x.fin))
        is WhenE -> WhenE(x.subj?.let { e(it) }, x.branches.map { (c, b) -> c?.map { e(it) } to block(b) })
    }.also { if (it !== x) it.line = x.line }
}
