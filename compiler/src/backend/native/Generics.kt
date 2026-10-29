package newkotlin

/**
 * Generic classes are monomorphized: `class Box<T>` is never compiled itself; every type-argument list used by the
 * program (`Box<Int>`, `Box<String>`) becomes a plain class `Box$Int` / `Box$String` with T substituted in its AST.
 * Instances are discovered during code generation ([NeedInstance]); the class is added and generation restarts,
 * until the instance set is closed. Each instance has exact field types, so primitives stay unboxed and the
 * Perceus RC / cycle analysis (cyclic classes, field release functions) is computed per instance.
 */
fun compileGenerics(p00: Program): String {
    val p0 = rewriteStatics(p00)
    val generic = p0.classes.filter { it.typeParams.isNotEmpty() }.associateBy { it.name }
    val inst = LinkedHashMap<String, ClassD>()
    val origin = HashMap<String, Pair<String, List<TypeRef>>>()
    while (true) {
        val prog = Program(p0.funs, p0.classes.filter { it.typeParams.isEmpty() } + inst.values, p0.ifaces, p0.globals)
        try {
            return NativeBackend(prog, generic, origin).generate()
        } catch (n: NeedInstance) {
            val m = mangle(n.name, n.args)
            if (m in inst) throw CompileError("internal: instance $m requested twice")
            if (inst.size >= 1000) throw CompileError("too many generic class instances (recursive generic type?)")
            val cd = generic.getValue(n.name)
            origin[m] = n.name to n.args
            inst[m] = Subst(cd.typeParams.zip(n.args).toMap()).cls(cd, m)
        }
    }
}

/** `Box` + [Int, ArrayList<String>] -> `Box$Int$ArrayList$String$$`: a valid LLVM identifier, unique per argument list. */
fun mangle(n: String, args: List<TypeRef>): String =
    n + args.joinToString("") { "$" + it.toString().replace("<", "$").replace(">", "$$").replace(",", "$").replace("?", "_N") }

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
        Subst(emptyMap()) { x ->
            when {
                x is NameE && x.n in own -> NameE("${c.name}$${x.n}").also { it.line = x.line }
                x is NameE && comp != null && x.n in comp.second && x.n !in mine && comp.first != "${c.name}$${x.n}" ->
                    MemberE(NameE(comp.first), x.n, false).also { it.line = x.line }
                else -> global(x)
            }
        }.cls(c, c.name, keepTypeParams = true)
    }
    return Program(p.funs.map { sub.fn(it) }, classes, p.ifaces, p.globals.map { sub.s(it) as VarS })
}

/** Substitutes type parameters by concrete type references throughout a class declaration ([rw]: optional expression rewrite). */
private class Subst(val env: Map<String, TypeRef>, val rw: ((E) -> E?)? = null) {
    val fieldMemo = HashMap<FieldD, FieldD>()

    fun ty(t: TypeRef): TypeRef = env[t.name]?.let { if (t.nullable) TypeRef(it.name, it.args, true) else it }
        ?: TypeRef(t.name, t.args.map { ty(it) }, t.nullable)

    fun cls(c: ClassD, name: String, keepTypeParams: Boolean = false): ClassD {
        val fields = c.fields.map { field(it) }
        return ClassD(name, fields, c.supers, c.methods.map { fn(it) }, c.line, c.ctorParams.map { Param(it.name, ty(it.type)) },
            c.inits.map { if (it is FieldD) field(it) else block(it as Block) }, c.superArgs?.map { e(it) },
            if (keepTypeParams) c.typeParams else emptyList(), c.enumEntries?.map { (n, a) -> n to a.map { e(it) } }, c.isValue)
    }

    fun field(f: FieldD): FieldD = fieldMemo.getOrPut(f) { FieldD(f.name, f.type?.let { ty(it) }, f.isVar, f.init?.let { e(it) }) }

    fun fn(f: FunD): FunD = FunD(f.name, f.params.map { Param(it.name, ty(it.type)) }, f.ret?.let { ty(it) },
        f.body?.let { block(it) }, f.exprBody?.let { e(it) }, f.line, f.typeParams)

    fun block(b: Block): Block = Block(b.stmts.map { s(it) }, b.params)
    fun blockN(b: Block?): Block? = b?.let { block(it) }

    fun s(x: S): S = when (x) {
        is VarS -> VarS(x.name, x.isVar, x.type?.let { ty(it) }, e(x.init))
        is AssignS -> AssignS(e(x.target), x.op, e(x.value))
        is IncS -> IncS(e(x.target), x.op)
        is WhileS -> WhileS(e(x.cond), block(x.body))
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
        is IfE -> IfE(e(x.cond), block(x.th), blockN(x.el))
        is WhenE -> WhenE(x.subj?.let { e(it) }, x.branches.map { (c, b) -> c?.map { e(it) } to block(b) })
    }.also { if (it !== x) it.line = x.line }
}
