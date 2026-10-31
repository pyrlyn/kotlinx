package newkotlin

private val ASSIGN_OPS = setOf("=", "+=", "-=", "*=", "/=", "%=")
private val IN_OPS = setOf("in", "!in")
private val INFIX_FUNS = setOf("and", "or", "xor", "shl", "shr", "ushr", "until", "downTo", "step")
private val GENERIC_CALLS = setOf("ArrayList", "mutableListOf", "arrayListOf", "listOf", "HashMap", "hashMapOf", "mutableMapOf")
private val LAMBDA_METHODS = setOf("usePinned", "map", "filter", "forEach", "sumOf", "count")

/** Names after which `{` does not start a trailing lambda. */
private val NO_TRAILING = setOf("try", "do", "else", "finally", "init", "object", "catch", "return", "this")

private val MEMBER_MODS = setOf("override", "open", "public", "private", "protected", "internal", "final", "abstract", "const", "operator", "infix", "sealed", "lateinit")

/**
 * Upper bounds of type parameters, by "<declaration line>:<name>" (declarations are copied and renamed by later
 * passes but keep their line). Only consulted for semantics that depend on the bound (unchecked casts).
 */
object TypeParamBounds {
    val bounds = HashMap<String, TypeRef>()
    val unbounded = HashSet<String>()
}

/** Names of lifted local declarations (a local function wins over a member of an implicit receiver). */
object LiftedLocals { val names = HashSet<String>(); var usesRef = false }

private var argTmp = 0

class Parser(toks: List<Tok>) {
    /** Tokens padded with extra EOF tokens, so lookahead (`t[p + 1]`) at the end of an unclosed file is an error, not a crash. */
    private val t: List<Tok> = if (toks.isNotEmpty() && toks.last().k == K.EOF) toks + List(8) { toks.last() } else toks
    /** Names of `infix fun`s declared in the source (`a name b` parses as `a.name(b)`). */
    private val userInfix: Set<String> = hashSetOf("to").also { set ->
        for (i in t.indices) if (t[i].k == K.ID && t[i].s == "infix") {
            var j = i + 1
            while (j < t.size && !(t[j].k == K.OP && t[j].s == "(")) j++
            if (j < t.size && t[j - 1].k == K.ID) set.add(t[j - 1].s)
        }
    }
    private var destructN = 0
    /** Extra statements produced by a destructuring declaration, emitted after the one [stmt] returned. */
    private val pendingStmts = ArrayList<S>()
    /** Classes of anonymous objects (`object : T { }` expressions) declared before the current statement. */
    private var pendingDecls = ArrayList<S>()
    /** Anonymous-object classes outside function bodies (property initializers): top-level classes. */
    private val topAnon = ArrayList<ClassD>()
    private var bodyLevel = 0
    private var anonN = 0

    /** Data class: generated toString, componentN, hashCode and `$equals` (structural `==`) over the constructor properties. */
    private fun dataClass(c: ClassD): ClassD {
        val props = c.ctorParams.filter { p -> c.fields.any { it.name == p.name } }
        // `Array<T>` is a list here: its `==` would compare contents, not identity as in Kotlin.
        if (props.any { it.type.name == "Array" }) fail("data classes with Array properties are not supported")
        val have = c.methods.map { it.name }.toSet()
        val ms = ArrayList(c.methods)
        val l = c.line
        fun nm(n: String) = NameE(n).also { it.line = l }
        fun call(r: E, n: String) = CallE(MemberE(r, n, false).also { it.line = l }, emptyList(), emptyList(), null).also { it.line = l }
        if ("toString" !in have) {
            val parts = ArrayList<Any>()
            props.forEachIndexed { i, p -> parts.add((if (i == 0) c.name + "(" else ", ") + p.name + "="); parts.add(nm(p.name)) }
            parts.add(if (props.isEmpty()) c.name + "()" else ")")
            ms.add(FunD("toString", emptyList(), TypeRef("String", emptyList(), false), null, StrL(parts).also { it.line = l }, l))
        }
        props.forEachIndexed { i, p -> if ("component${i + 1}" !in have) ms.add(FunD("component${i + 1}", emptyList(), p.type, null, nm(p.name), l)) }
        if ("hashCode" !in have) {
            var h: E = IntL(0, false)
            props.forEachIndexed { i, p ->
                val ph = call(nm(p.name), "hashCode")
                h = if (i == 0) ph else BinE("+", BinE("*", h, IntL(31, false)).also { it.line = l }, ph).also { it.line = l }
            }
            ms.add(FunD("hashCode", emptyList(), TypeRef("Int", emptyList(), false), null, h, l))
        }
        val self = TypeRef(c.name, c.typeParams.map { TypeRef(it, emptyList(), false) }, false)
        var eq: E = BoolL(true)
        props.forEachIndexed { i, p ->
            // Double properties compare like Double.equals (bits: NaN == NaN, 0.0 != -0.0).
            fun bits(e: E): E = if (p.type.name == "Double" || p.type.name == "Float") CallE(MemberE(e, "\$bits", false).also { it.line = l }, emptyList(), emptyList(), null).also { it.line = l } else e
            val one = BinE("==", bits(nm(p.name)), bits(MemberE(nm("other"), p.name, false).also { it.line = l })).also { it.line = l }
            eq = if (i == 0) one else BinE("&&", eq, one).also { it.line = l }
        }
        ms.add(FunD("\$equals", listOf(Param("other", self)), TypeRef("Boolean", emptyList(), false), null, eq, l))
        return ClassD(c.name, c.fields, c.supers, ms, c.line, c.ctorParams, c.inits, c.superArgs, c.typeParams, c.enumEntries, c.isValue, c.superTypeArgs)
    }

    private var p = 0
    /** Newlines are insignificant while inside (...) or [...]; a `{` block resets that. */
    private val depth = ArrayList<Int>().apply { add(0) }

    private fun fail(msg: String, tk: Tok = t[p]): Nothing = throw CompileError("line ${tk.line}: $msg (at '${tk.s}')")
    private fun peek(): Tok { if (depth.last() > 0) while (t[p].k == K.NL) p++; return t[p] }
    private fun next(): Tok { val x = peek(); p++; return x }
    private fun isOp(s: String) = peek().let { it.k == K.OP && it.s == s }
    private fun isId(s: String) = peek().let { it.k == K.ID && it.s == s }
    private fun expectOp(s: String) { val x = next(); if (!(x.k == K.OP && x.s == s)) fail("expected '$s'", x) }
    private fun ident(): String { val x = next(); if (x.k != K.ID) fail("expected identifier", x); return x.s }
    private fun skipNL() { while (t[p].k == K.NL || (t[p].k == K.OP && t[p].s == ";")) p++ }
    private fun skipNL0() { while (t[p].k == K.NL) p++ }
    private fun skipNLOnly() { while (t[p].k == K.NL) p++ }
    private fun skipLine() { while (t[p].k != K.NL && t[p].k != K.EOF) p++ }
    private inline fun <T> nested(d: Int, f: () -> T): T { depth.add(d); try { return f() } finally { depth.removeAt(depth.size - 1) } }

    /**
     * Extension functions on a (non-generic, or generic with the extension's own type parameters as arguments) class
     * of this program become methods of that class (member semantics for `this` and implicit receivers); others become
     * top-level functions `ext$Recv$name(this: Recv, ...)`, which the backend calls for `recv.name(...)`.
     */
    /** `get() = e` / `get() { ... }` after a property declaration (possibly on the next line): the getter as a function. */
    private fun getter(fname: String, ty: TypeRef?, typeParams: List<String>, recv: TypeRef?): FunD? {
        val save = p
        while (t[p].k == K.NL) p++
        if (!(isId("get") && t[p + 1].k == K.OP && t[p + 1].s == "(")) { p = save; return null }
        val line = next().line
        expectOp("("); expectOp(")")
        val rt = if (isOp(":")) { next(); type() } else ty
        return if (isOp("=")) { next(); skipNLOnly(); FunD(fname, emptyList(), rt, null, expr(), line, typeParams, recv) }
        else FunD(fname, emptyList(), rt, block(), null, line, typeParams, recv)
    }

    /**
     * Accessors after a body property with an initializer (`var x: T = v` + `get() = ...` / `set(v) { field = v }`):
     * the backing field becomes `$bf$x`, the getter `$prop$x`, the setter `$set$x(v)`; `field` names the backing field.
     */
    private fun accessors(n: String, ty: TypeRef?, f: FieldD, isVar: Boolean): Pair<FieldD, List<FunD>>? {
        val save = p
        var get: FunD? = null; var set: FunD? = null
        while (true) {
            val at = p
            while (t[p].k == K.NL) p++
            while (peek().k == K.ID && peek().s in setOf("private", "protected", "internal", "public") && t[p + 1].k == K.ID && t[p + 1].s in setOf("get", "set")) p++
            if (get == null && isId("get") && t[p + 1].k == K.OP && t[p + 1].s == "(") { get = getter("\$prop\$$n", ty, emptyList(), null); continue }
            if (set == null && isId("set") && t[p + 1].k == K.OP && t[p + 1].s == "(") {
                val line = next().line; next()
                val pn = ident(); if (isOp(":")) { next(); type() }
                expectOp(")")
                val body = if (isOp("=")) { next(); skipNLOnly(); Block(listOf(ExprS(expr()).also { it.line = line })) } else block()
                set = FunD("\$set\$$n", listOf(Param(pn, ty ?: fail("properties with a setter need an explicit type"))), TypeRef("Unit", emptyList(), false), body, null, line)
                continue
            }
            // A bare `private set` (visibility only) keeps the default setter.
            if (set == null && isId("set") && !(t[p + 1].k == K.OP && t[p + 1].s == "(")) { val line = next().line
                set = FunD("\$set\$$n", listOf(Param("v\$", ty ?: fail("properties with a setter need an explicit type"))), TypeRef("Unit", emptyList(), false),
                    Block(listOf(AssignS(NameE("field"), "=", NameE("v\$")).also { it.line = line })), null, line); continue }
            p = at; break
        }
        if (get == null && set == null) { p = save; return null }
        if (set != null && !isVar) fail("val cannot have a setter")
        val bfn = "\$bf\$$n"
        val sub = Subst(emptyMap(), rw = { x: E -> if (x is NameE && x.n == "field") NameE(bfn).also { it.line = x.line } else null },
            srw = { st: S -> if (st is AssignS && st.target is NameE && (st.target as NameE).n == "field") AssignS(NameE(bfn), st.op, sub0!!.e(st.value)).also { it.line = st.line } else null })
        sub0 = sub
        val g = get?.let { sub.fn(it) } ?: FunD("\$prop\$$n", emptyList(), ty, null, NameE(bfn), f.init?.line ?: 0)
        val st = set?.let { sub.fn(it) } ?: if (isVar) FunD("\$set\$$n", listOf(Param("v\$", ty ?: fail("properties with accessors need an explicit type"))), TypeRef("Unit", emptyList(), false),
            Block(listOf(AssignS(NameE(bfn), "=", NameE("v\$")))), null, g.line) else null
        val bf = FieldD(bfn, f.type, true, f.init, f.lateinit)
        return bf to listOfNotNull(g, st)
    }
    private var sub0: Subst? = null

    /**
     * Top-level computed property `val x: T get() = ...` / `var x: T get() ... set(v) ...` (no backing field, possibly
     * with context parameters): the getter `$get$x()` (read like an object accessor), the setter `$tset$x(v)`.
     */
    /**
     * Delegated property `val x: T by d` (no `KProperty` metadata): a hidden field `$dg$x = d`, the getter
     * `d.getValue(thisRef, null)` and for `var` the setter `d.setValue(thisRef, null, v)`.
     */
    private fun delegated(n: String, ty: TypeRef?, isVar: Boolean, thisRef: E, gname: String, sname: String): Pair<FieldD, List<FunD>> {
        val line = next().line; skipNLOnly()
        if (ty == null && isVar) fail("delegated var properties need an explicit type")
        val dn = "\$dg\$$n"
        val d = dropLazyMode(expr())
        fun call(m: String, extra: List<E>) = CallE(MemberE(NameE(dn).also { it.line = line }, m, false).also { it.line = line }, emptyList(), listOf(thisRef, NullL()) + extra, null).also { it.line = line }
        // `by lazy { }` (the prelude's Lazy): read its `value`.
        val g = FunD(gname, emptyList(), ty, null, if (isLazyCall(d)) MemberE(NameE(dn).also { it.line = line }, "value", false).also { it.line = line } else call("getValue", emptyList()), line)
        val s = if (isVar) FunD(sname, listOf(Param("v\$", ty!!)), TypeRef("Unit", emptyList(), false),
            Block(listOf(ExprS(call("setValue", listOf(NameE("v\$")))).also { it.line = line })), null, line) else null
        // Without a declared type the getter's type is inferred from `getValue`.
        return FieldD(dn, null, false, d) to listOfNotNull(g, s)
    }
    /** `lazy(LazyThreadSafetyMode.X) { }` -> `lazy { }` (single-threaded, the mode does not matter). */
    private fun dropLazyMode(d: E): E = if (isLazyCall(d) && d is CallE && d.lambda != null && d.args.size == 1 && (d.args[0] as? MemberE)?.recv.let { it is NameE && it.n == "LazyThreadSafetyMode" })
        CallE(d.callee, d.typeArgs, emptyList(), d.lambda).also { it.line = d.line } else d
    private fun isLazyCall(d: E) = d is CallE && (d.callee as? NameE)?.n == "lazy"
    /** Delegated locals initialized with `lazy { }`. */
    private var lazyLocals = HashSet<String>()
    /** Top-level delegated property: its hidden global, added by program() in declaration order. */
    private var pendingGlobal: VarS? = null

    private fun topProperty(funs: MutableList<FunD>): Boolean {
        val save = p
        val isVar = next().s == "var"
        if (peek().k == K.ID && t[p + 1].k == K.ID && t[p + 1].s == "by") {
            val n = ident()
            val (df, acc) = delegated(n, null, isVar, NullL(), "\$get\$$n", "\$tset\$$n")
            pendingGlobal = VarS(df.name, false, null, df.init!!).also { it.line = acc[0].line }
            funs.addAll(acc); return true
        }
        if (peek().k != K.ID || !(t[p + 1].k == K.OP && t[p + 1].s == ":")) { p = save; return false }
        val n = ident(); next(); val ty = type()
        if (isId("by")) {
            if (pendingCtx != null) noCtx()
            val (df, acc) = delegated(n, ty, isVar, NullL(), "\$get\$$n", "\$tset\$$n")
            pendingGlobal = VarS(df.name, false, null, df.init!!).also { it.line = acc[0].line }
            funs.addAll(acc); return true
        }
        val ctx = pendingCtx ?: emptyList()
        var get: FunD? = null; var set: FunD? = null
        while (true) {
            val at = p
            while (t[p].k == K.NL) p++
            if (get == null && isId("get") && t[p + 1].k == K.OP && t[p + 1].s == "(") { get = getter("\$get\$$n", ty, emptyList(), null); continue }
            if (set == null && isVar && isId("set") && t[p + 1].k == K.OP && t[p + 1].s == "(") {
                val line = next().line; next()
                val pn = ident(); if (isOp(":")) { next(); type() }
                expectOp(")")
                val body = if (isOp("=")) { next(); skipNLOnly(); Block(listOf(ExprS(expr()).also { it.line = line })) } else block()
                set = FunD("\$tset\$$n", ctx + Param(pn, ty), TypeRef("Unit", emptyList(), false), body, null, line)
                continue
            }
            p = at; break
        }
        if (get == null || (isVar && set == null)) { p = save; return false }
        pendingCtx = null
        funs.add(FunD(get.name, ctx + get.params, get.ret, get.body, get.exprBody, get.line))
        set?.let { funs.add(it) }
        return true
    }

    /** Type parameters of the interface whose body is being parsed. */
    private var memberTps: List<String> = emptyList()
    /** Top-level `fun interface` declarations by name. */
    private val funIfaces = LinkedHashMap<String, InterfaceD>()

    /**
     * `fun interface I { fun m(a: A): R }`: a class `I$Sam(f$: (A) -> R) : I` whose `m` calls `f$`.
     * `I { ... }` / `I(f)` construct it; a lambda passed where a parameter of a top-level function, constructor or
     * (uniquely named) method has type `I` is wrapped the same way (SAM conversion).
     */
    private fun samLower(funs: MutableList<FunD>, classes: MutableList<ClassD>, ifaces: MutableList<InterfaceD>, globals: MutableList<VarS>) {
        if (funIfaces.isEmpty()) return
        for ((n, d) in funIfaces) {
            if (ifaceSupers[n].orEmpty().isNotEmpty()) fail("fun interfaces with super-interfaces are not supported", Tok(K.ID, n, d.line))
            val defs = ifaceDefaults[n].orEmpty().map { it.name }.toSet()
            val abs = d.methods.filter { it.name !in defs }
            val m = abs.singleOrNull() ?: fail("a fun interface needs exactly one abstract method", Tok(K.ID, n, d.line))
            if (m.recv != null || m.typeParams.isNotEmpty() || m.name.startsWith("\$")) fail("this fun interface method is not supported", Tok(K.ID, n, d.line))
            val ret = m.ret ?: TypeRef("Unit", emptyList(), false)
            val fnT = TypeRef("\$Fn", m.params.map { it.type } + ret, false)
            val body = CallE(NameE("f\$").also { it.line = d.line }, emptyList(), m.params.map { NameE(it.name).also { e -> e.line = d.line } }, null).also { it.line = d.line }
            val tas = d.typeParams.map { TypeRef(it, emptyList(), false) }
            classes.add(ClassD("$n\$Sam", listOf(FieldD("f\$", fnT, false)), listOf(n), listOf(FunD(m.name, m.params, ret, null, body, d.line)), d.line,
                typeParams = d.typeParams, superTypeArgs = if (tas.isEmpty()) emptyMap() else mapOf(n to tas)))
        }
        // Parameter types of callables by name (methods only when the name is declared once).
        val sigs = HashMap<String, List<TypeRef>>()
        val counts = HashMap<String, Int>()
        for (f in funs) if (f.recv == null) { sigs[f.name] = f.params.map { it.type }; counts[f.name] = (counts[f.name] ?: 0) + 1 }
        for (c in classes) { sigs[c.name] = c.ctorParams.map { it.type }; counts[c.name] = (counts[c.name] ?: 0) + 1
            for (f in c.methods) { sigs[f.name] = f.params.map { it.type }; counts[f.name] = (counts[f.name] ?: 0) + 1 } }
        for (d in ifaces) for (f in d.methods) { sigs[f.name] = f.params.map { it.type }; counts[f.name] = (counts[f.name] ?: 0) + 1 }
        // A generic fun interface needs its type arguments from the context (`I<String> { }` or the parameter type).
        fun sam(t: TypeRef?) = t != null && t.name in funIfaces && t.args.size == funIfaces[t.name]!!.typeParams.size
        var sb: Subst? = null
        fun wrap(i: String, x: E, line: Int, tas: List<TypeRef> = emptyList()) = CallE(NameE("$i\$Sam").also { it.line = line }, tas, listOf(x), null).also { it.line = line }
        sb = Subst(emptyMap(), rw = rw@{ x ->
            if (x !is CallE) return@rw null
            val s = sb!!
            val callee = x.callee
            val cn = (callee as? NameE)?.n ?: (callee as? MemberE)?.n ?: return@rw null
            val args = x.args.map { s.e(it) }
            val lam = x.lambda?.let { s.block(it) }
            if (callee is NameE && cn in funIfaces && x.typeArgs.size == funIfaces[cn]!!.typeParams.size) {
                val a = if (lam != null && args.isEmpty()) LambdaE(lam).also { it.line = x.line } else args.singleOrNull() ?: return@rw null
                return@rw wrap(cn, a, x.line, x.typeArgs)
            }
            val ps = sigs[cn] ?: return@rw null
            if (counts[cn] != 1 || args.any { it is NamedE }) return@rw null
            if (ps.indices.none { sam(ps[it]) }) return@rw null
            val na = args.mapIndexed { i, a -> if (a is LambdaE && sam(ps.getOrNull(i))) wrap(ps[i].name, a, x.line, ps[i].args) else a }
            val (na2, lam2) = if (lam != null && sam(ps.getOrNull(na.size))) (na + wrap(ps[na.size].name, LambdaE(lam).also { it.line = x.line }, x.line, ps[na.size].args)) to null else na to lam
            CallE(if (callee is MemberE) MemberE(s.e(callee.recv), callee.n, callee.safe).also { it.line = callee.line } else callee, x.typeArgs, na2, lam2).also { it.line = x.line; it.infix = x.infix }
        })
        val s = sb
        fun fn(f: FunD) = s.fn(f).let { g -> FunD(g.name, g.params, g.ret, g.body, g.exprBody, g.line, f.typeParams, f.recv) }
        for (i in funs.indices) funs[i] = fn(funs[i])
        for (i in classes.indices) { val c = classes[i]
            classes[i] = ClassD(c.name, c.fields.map { s.field(it) }, c.supers, c.methods.map { fn(it) }, c.line, c.ctorParams.map { Param(it.name, it.type, it.default?.let { d -> s.e(d) }, it.vararg, it.ctx) },
                c.inits.map { if (it is FieldD) s.field(it) else s.block(it as Block) }, c.superArgs?.map { s.e(it) }, c.typeParams, c.enumEntries?.map { (n, a) -> n to a.map { s.e(it) } }, c.isValue, c.superTypeArgs) }
        for (i in globals.indices) { val g = globals[i]; globals[i] = VarS(g.name, g.isVar, g.type, s.e(g.init)).also { it.line = g.line } }
    }

    /** (class, name) of private members of classes. */
    private val privateMembers = HashSet<Pair<String, String>>()

    /**
     * Semantics this compiler does not model, rejected instead of miscompiled:
     * - a private member of a superclass with the same name as an interface member of the subclass (Kotlin keeps
     *   them apart; here they would share one slot);
     * - a property initialized to a default value (0, false, null) after an `init` block or initializer that calls a
     *   function (Kotlin does not re-store default values, so a value set by that call survives);
     * (Also: `++`/`--` on a `Short` variable is marked to wrap at 16 bits, since Short is stored as Int.)
     */
    private fun rejectUnmodeled(funs: MutableList<FunD>, classes: MutableList<ClassD>, ifaces: List<InterfaceD>) {
        // Using an enum entry initializes the enum class and its companion in Kotlin; here companion init blocks
        // run only when the companion itself is used.
        for (e in enumClasses) { val co = companionOf[e]?.first ?: continue
            if (classes.any { it.name == co && it.inits.any { i -> i is Block } }) fail("init blocks in companions of enum classes are not supported") }
        val byName = classes.associateBy { it.name }
        val ifByName = ifaces.associateBy { it.name }
        for (c in classes) {
            val anc = generateSequence(c.superClass?.let { byName[it] }) { a -> a.superClass?.let { byName[it] } }.toList()
            val ifNames = c.supers.mapNotNull { ifByName[it] }.flatMap { d -> d.methods.map { it.name.removePrefix("\$prop\$") } }.toSet()
            for (a in anc) for ((pc, n) in privateMembers) if (pc == a.name && n in ifNames) fail("private member '$n' of ${a.name} and an interface member of ${c.name} with the same name are not supported")
            // Methods of the class called (unqualified or on `this`) by initializers so far, and the names they assign.
            val methods = c.methods.associateBy { it.name }
            val writes = HashSet<String>()
            fun scan(x: Any?) {
                val s = Subst(emptyMap(), rw = { e ->
                    if (e is CallE) { val n = (e.callee as? NameE)?.n ?: (e.callee as? MemberE)?.takeIf { (it.recv as? NameE)?.n == "this" }?.n
                        n?.let { methods[it] }?.let { assignedNames(it, writes) } }
                    null })
                when (x) { is E -> s.e(x); is Block -> s.block(x) }
            }
            for (i in c.inits) {
                if (i is FieldD) {
                    val z = i.init
                    if (i.name in writes && !i.lateinit && (z is NullL || z is BoolL && !z.v || z is IntL && z.v == 0L || z is DblL && z.v == 0.0))
                        fail("property '${i.name}' initialized to a default value after an initializer that sets it is not supported")
                    scan(z)
                } else scan(i)
            }
        }
        fun shortsFix(f: FunD): FunD {
            val shorts = HashSet<String>()
            Subst(emptyMap(), srw = { s ->
                if (s is VarS && s.isVar && (s.type?.name == "Short" || (s.init is MemberE && (s.init.recv as? NameE)?.n == "Short") ||
                        (s.init is CallE && (s.init.callee as? MemberE)?.n == "toShort"))) shorts.add(s.name)
                null
            }).fn(f)
            if (shorts.isEmpty()) return f
            // `s++` / `--s` on a Short: the op gets the suffix "s16", so the backend wraps the new value at 16 bits.
            val g = Subst(emptyMap(), rw = { e -> if (e is PostIncE && (e.target as? NameE)?.n in shorts && !e.op.endsWith("s16")) PostIncE(e.target, e.op + "s16").also { it.line = e.line } else null },
                srw = { s -> if (s is IncS && (s.target as? NameE)?.n in shorts && !s.op.endsWith("s16")) IncS(s.target, s.op + "s16").also { it.line = s.line } else null }).fn(f)
            return FunD(g.name, g.params, g.ret, g.body, g.exprBody, g.line, f.typeParams, f.recv)
        }
        for (i in funs.indices) funs[i] = shortsFix(funs[i])
        for (i in classes.indices) { val c = classes[i]; val ms = c.methods.map { shortsFix(it) }
            if (ms.zip(c.methods).any { (a, b) -> a !== b }) classes[i] = ClassD(c.name, c.fields, c.supers, ms, c.line, c.ctorParams, c.inits, c.superArgs, c.typeParams, c.enumEntries, c.isValue, c.superTypeArgs) }
    }

    /** Names of member properties with context parameters (accessed through `$cget$x` / `$cset$x` methods). */
    private val ctxProps = HashSet<String>()

    /**
     * Member property with context parameters, `context(c: C) var x: T get() ... set(v) ...` (no backing field):
     * the getter method `$cget$x(c)`, the setter `$cset$x(c, v)`; abstract in interfaces / abstract classes.
     * Reads `r.x` and assignments `r.x = v` are rewritten to these calls by [rewriteCtxProps].
     */
    private fun ctxMemberProp(ms: MutableList<FunD>, abstractOk: Boolean) {
        val line = peek().line
        val isVar = next().s == "var"
        if (isOp("<")) fail("generic properties with context parameters are not supported")
        val n = ident()
        if (!isOp(":")) fail("properties with context parameters need an explicit type")
        next(); val ty = type()
        val ctx = takeCtx()
        var get: FunD? = null; var set: FunD? = null
        while (true) {
            val at = p
            while (t[p].k == K.NL) p++
            if (get == null && isId("get") && t[p + 1].k == K.OP && t[p + 1].s == "(") { get = getter("\$cget\$$n", ty, emptyList(), null); continue }
            if (set == null && isVar && isId("set") && t[p + 1].k == K.OP && t[p + 1].s == "(") {
                val sl = next().line; next()
                val pn = ident(); if (isOp(":")) { next(); type() }
                expectOp(")")
                val body = if (isOp("=")) { next(); skipNLOnly(); Block(listOf(ExprS(expr()).also { it.line = sl })) } else block()
                set = FunD("\$cset\$$n", ctx + Param(pn, ty), TypeRef("Unit", emptyList(), false), body, null, sl)
                continue
            }
            p = at; break
        }
        if (get == null && set == null) {
            if (!abstractOk) fail("properties with context parameters need a getter")
            ms.add(FunD("\$cget\$$n", ctx, ty, null, null, line))
            if (isVar) ms.add(FunD("\$cset\$$n", ctx + Param("v\$", ty), TypeRef("Unit", emptyList(), false), null, null, line))
        } else {
            if (get == null || (isVar && set == null)) fail("properties with context parameters need both accessors")
            ms.add(FunD(get.name, ctx + get.params, get.ret, get.body, get.exprBody, get.line))
            set?.let { ms.add(it) }
        }
        ctxProps.add(n)
    }

    /** `r.x` -> `r.$cget$x()`, `r.x = v` -> `r.$cset$x(v)` (and unqualified `x` inside classes) for [ctxProps]. */
    private fun rewriteCtxProps(funs: MutableList<FunD>, classes: MutableList<ClassD>, ifaces: MutableList<InterfaceD>, globals: MutableList<VarS>) {
        if (ctxProps.isEmpty()) return
        for (c in classes) if (c.fields.any { it.name in ctxProps } || c.ctorParams.any { it.name in ctxProps }) fail("a property '${c.fields.first { it.name in ctxProps }.name}' with and without context parameters is not supported")
        fun get(r: E?, n: String, l: Int): E = CallE((if (r == null) NameE("\$cget\$$n") else MemberE(r, "\$cget\$$n", false)).also { it.line = l }, emptyList(), emptyList(), null).also { it.line = l }
        lateinit var sb: Subst
        sb = Subst(emptyMap(), rw = { x ->
            when {
                x is CallE && x.callee is MemberE && x.callee.n in ctxProps -> fail("calling a property with context parameters is not supported")
                x is MemberE && x.n in ctxProps -> { if (x.safe) fail("safe access to a property with context parameters is not supported"); get(sb.e(x.recv), x.n, x.line) }
                x is NameE && x.n in ctxProps -> get(null, x.n, x.line)
                x is PostIncE && ((x.target is MemberE && x.target.n in ctxProps) || (x.target is NameE && x.target.n in ctxProps)) -> fail("++/-- on a property with context parameters is not supported")
                else -> null
            }
        }, srw = { s ->
            val tg = (s as? AssignS)?.target
            val n = when (tg) { is MemberE -> tg.n; is NameE -> tg.n; else -> null }
            if (s !is AssignS || n == null || n !in ctxProps) null
            else {
                if (tg is MemberE && tg.safe) fail("safe assignment to a property with context parameters is not supported")
                val r = (tg as? MemberE)?.recv
                if (s.op != "=" && r != null && r !is NameE) fail("compound assignment to a context property needs a simple receiver")
                val rr = r?.let { sb.e(it) }
                val v = if (s.op == "=") sb.e(s.value) else BinE(s.op.dropLast(1), get(rr, n, s.line), sb.e(s.value)).also { it.line = s.line }
                val callee = (if (rr == null) NameE("\$cset\$$n") else MemberE(rr, "\$cset\$$n", false)).also { it.line = s.line }
                ExprS(CallE(callee, emptyList(), listOf(v), null).also { it.line = s.line }).also { it.line = s.line }
            }
        })
        fun fn(f: FunD) = sb.fn(f).let { g -> FunD(g.name, g.params, g.ret, g.body, g.exprBody, g.line, f.typeParams, f.recv) }
        for (i in funs.indices) funs[i] = fn(funs[i])
        for (i in classes.indices) { val c = classes[i]
            classes[i] = ClassD(c.name, c.fields.map { sb.field(it) }, c.supers, c.methods.map { fn(it) }, c.line, c.ctorParams.map { Param(it.name, it.type, it.default?.let { d -> sb.e(d) }, it.vararg, it.ctx) },
                c.inits.map { if (it is FieldD) sb.field(it) else sb.block(it as Block) }, c.superArgs?.map { sb.e(it) }, c.typeParams, c.enumEntries?.map { (n, a) -> n to a.map { sb.e(it) } }, c.isValue, c.superTypeArgs) }
        for (i in ifaces.indices) { val d = ifaces[i]; ifaces[i] = InterfaceD(d.name, d.methods.map { fn(it) }, d.line, d.typeParams, d.supers) }
        for (i in globals.indices) { val g = globals[i]; globals[i] = VarS(g.name, g.isVar, g.type, sb.e(g.init)).also { it.line = g.line } }
    }

    /** Extension property `val <T> Recv.name: Type get() = ...` (getter only): an extension function `$prop$name`. */
    private fun extProperty(): FunD {
        val ctx = takeCtx()
        return extProperty0().let { g -> FunD(g.name, ctx + g.params, g.ret, g.body, g.exprBody, g.line, g.typeParams, g.recv) }
    }

    private fun extProperty0(): FunD {
        next()
        val tps = typeParams()
        val rt = type()
        val (recv, name) = if (isOp("?.")) { next(); TypeRef(rt.name, rt.args, true) to ident() } else if (isOp(".")) { next(); rt to ident() }
            else if (rt.args.isEmpty() && rt.name.contains('.')) TypeRef(rt.name.substringBeforeLast('.'), emptyList(), false) to rt.name.substringAfterLast('.')
            else fail("expected extension property")
        val ty = if (isOp(":")) { next(); type() } else null
        return getter("\$prop\$$name", ty, tps, recv) ?: fail("extension properties need a getter")
    }

    private fun resolveExtensions(funs: MutableList<FunD>, classes: MutableList<ClassD>) {
        for (f in funs.filter { it.recv != null }) {
            val recv = f.recv!!
            funs.remove(f)
            val ci = classes.indexOfFirst { it.name == recv.name }
            val cd = if (ci >= 0) classes[ci] else null
            val asMethod: FunD? = when {
                cd == null -> null
                cd.typeParams.isEmpty() && recv.args.isEmpty() -> FunD(f.name, f.params, f.ret, f.body, f.exprBody, f.line, f.typeParams)
                cd.typeParams.size == recv.args.size && recv.args.all { it.name in f.typeParams && it.args.isEmpty() } && recv.args.map { it.name }.toSet().size == recv.args.size -> {
                    val m = recv.args.mapIndexed { i, a -> a.name to TypeRef(cd.typeParams[i], emptyList(), false) }.toMap()
                    Subst(m).fn(FunD(f.name, f.params, f.ret, f.body, f.exprBody, f.line, f.typeParams.filter { it !in m }))
                }
                else -> null
            }
            if (cd != null && asMethod != null) {
                if (cd.methods.none { it.name == f.name }) classes[ci] = ClassD(cd.name, cd.fields, cd.supers, cd.methods + asMethod, cd.line, cd.ctorParams,
                    cd.inits, cd.superArgs, cd.typeParams, cd.enumEntries, cd.isValue, cd.superTypeArgs)
                continue
            }
            funs.add(FunD("ext$" + recv.name.replace('.', '_') + "$" + f.name, listOf(Param("this", recv)) + f.params, f.ret, f.body, f.exprBody, f.line, f.typeParams))
        }
    }

    fun program(): Program {
        val globals = ArrayList<VarS>(); val funs = ArrayList<FunD>(); val classes = ArrayList<ClassD>(); val ifaces = ArrayList<InterfaceD>()
        collectAliases()
        while (true) {
            skipNL()
            val tk = peek()
            when {
                tk.k == K.EOF -> break
                tk.k == K.OP && tk.s == "@" -> skipLine()
                tk.k == K.ID && tk.s == "typealias" && t[p + 1].k == K.ID && t[p + 2].k == K.OP && t[p + 2].s == "=" -> { p += 3; type() }
                tk.k == K.ID && tk.s == "typealias" -> fail("generic type aliases are not supported", tk)
                // Annotation declarations have no runtime effect here.
                tk.k == K.ID && tk.s == "annotation" && t[p + 1].k == K.ID && t[p + 1].s == "class" -> { p++; classDecl() }
                tk.k == K.ID && (tk.s == "import" || tk.s == "package") -> skipLine()
                tk.k == K.ID && (tk.s == "value" || tk.s == "inline") && t[p + 1].k == K.ID && t[p + 1].s == "class" -> {
                    p++
                    val c = classDecl()
                    if (c.fields.size != 1 || c.ctorParams.size != 1) fail("a value class needs exactly one property", tk)
                    // hashCode of a value class is its property's hashCode.
                    // A type parameter counts by its declared bound (`value class Z<T: Int>(val x: T)`).
                    val ft0 = c.fields[0].type
                    val ft = if (ft0 != null && ft0.name in c.typeParams && !ft0.nullable) TypeParamBounds.bounds["${c.line}:${ft0.name}"] else ft0
                    val hashable = ft?.let { it.name in setOf("Int", "Long", "Short", "Byte", "Char", "Boolean", "String", "Double", "Float", "UInt", "ULong") && !it.nullable } == true
                    val ms = if (!hashable || c.methods.any { it.name == "hashCode" && it.params.isEmpty() }) c.methods else c.methods + FunD("hashCode", emptyList(), TypeRef("Int", emptyList(), false), null,
                        CallE(MemberE(NameE(c.fields[0].name).also { it.line = c.line }, "hashCode", false).also { it.line = c.line }, emptyList(), emptyList(), null).also { it.line = c.line }, c.line)
                    classes.add(ClassD(c.name, c.fields, c.supers, ms, c.line, c.ctorParams, c.inits, c.superArgs, c.typeParams, null, true, c.superTypeArgs))
                }
                tk.k == K.ID && tk.s == "data" && t[p + 1].k == K.ID && t[p + 1].s == "class" -> { p++; classes.add(dataClass(classDecl())) }
                tk.k == K.ID && tk.s in setOf("private", "internal", "public", "inline", "data", "operator", "infix", "tailrec", "const", "open", "final", "abstract", "sealed") -> p++
                tk.k == K.ID && (tk.s == "val" || tk.s == "var") && topProperty(funs) -> { pendingGlobal?.let { globals.add(it) }; pendingGlobal = null }
                tk.k == K.ID && (tk.s == "val" || tk.s == "var") && (t[p + 1].k == K.OP && t[p + 1].s == "<" ||
                    t[p + 1].k == K.ID && t[p + 2].k == K.OP && (t[p + 2].s == "." || t[p + 2].s == "<")) -> funs.add(extProperty())
                tk.k == K.ID && (tk.s == "val" || tk.s == "var") -> { noCtx(); globals.add(stmt() as VarS); if (pendingStmts.isNotEmpty()) fail("top-level destructuring declarations are not supported", tk) }
                tk.k == K.ID && tk.s == "fun" && t[p + 1].k == K.ID && t[p + 1].s == "interface" -> { p++; ifaces.add(interfaceDecl().also { funIfaces[it.name] = it }) }
                tk.k == K.ID && tk.s == "fun" -> funs.add(function())
                isContextDecl() -> contextDecl()
                tk.k == K.ID && (tk.s == "val" || tk.s == "var" || tk.s == "class" || tk.s == "object" || tk.s == "interface") && pendingCtx != null -> noCtx()
                tk.k == K.ID && tk.s == "class" -> classes.add(classDecl())
                tk.k == K.ID && tk.s == "enum" && t[p + 1].k == K.ID && t[p + 1].s == "class" -> { p++; classes.add(classDecl(true)) }
                tk.k == K.ID && tk.s == "interface" -> ifaces.add(interfaceDecl())
                tk.k == K.ID && tk.s == "object" -> { next(); classes.add(objectDecl(ident())) }
                else -> fail("unexpected top-level token")
            }
        }
        classes.addAll(companions); funs.addAll(ctorFactories)
        classes.addAll(hoisted); ifaces.addAll(hoistedIfaces)
        classes.addAll(topAnon); topAnon.clear()
        samLower(funs, classes, ifaces, globals)
        rejectUnmodeled(funs, classes, ifaces)
        rewriteCtxProps(funs, classes, ifaces, globals)
        liftLocals(funs, classes, ifaces)
        resolveNested(funs, classes, ifaces, globals)
        resolveExtensions(funs, classes)
        flattenIfaces(classes, ifaces)
        implementIfaceProps(classes, ifaces)
        // Creating an instance of a class initializes its companion object first.
        for (i in classes.indices) {
            val c = classes[i]
            val comp = companionOf[c.name]?.first ?: continue
            val trigger = Block(listOf(ExprS(CallE(NameE("\$init\$$comp").also { it.line = c.line }, emptyList(), emptyList(), null).also { it.line = c.line }).also { it.line = c.line }))
            classes[i] = ClassD(c.name, c.fields, c.supers, c.methods, c.line, c.ctorParams, listOf<Any>(trigger) + c.inits, c.superArgs, c.typeParams, c.enumEntries, c.isValue, c.superTypeArgs)
        }
        inlineConsts(funs, classes, globals)
        // Objects are created lazily on first access: `$o$N` holds the instance, `$get$N()` creates it once.
        // `$i$N` guards re-entry: constructing a class from inside its own companion's init must not recurse.
        val objGlobals = objects.flatMap { n -> listOf(VarS("\$o\$$n", true, TypeRef(n, emptyList(), true), NullL()), VarS("\$i\$$n", true, TypeRef("Boolean", emptyList(), false), BoolL(false))) }
        for (n in objects) {
            val slot = NameE("\$o\$$n"); val flag = NameE("\$i\$$n")
            val init = IfE(UnE("!", flag), Block(listOf(AssignS(flag, "=", BoolL(true)), AssignS(slot, "=", CallE(NameE(n), emptyList(), emptyList(), null)))), null)
            funs.add(FunD("\$init\$$n", emptyList(), TypeRef("Unit", emptyList(), false), Block(listOf(ExprS(init))), null, 0))
            funs.add(FunD("\$get\$$n", emptyList(), TypeRef(n, emptyList(), false), Block(listOf(ExprS(CallE(NameE("\$init\$$n"), emptyList(), emptyList(), null)), RetS(slot))), null, 0))
        }
        // Object slots come first: a top-level property initializer may already access an object.
        return Program(funs, classes, ifaces, objGlobals + enumGlobals(classes) + globals, companionOf)
    }

    /**
     * Object declarations (singletons): each is a class plus a top-level instance of the same name, initialized
     * eagerly after the other top-level properties (Kotlin initializes objects lazily on first access).
     */
    private val objects = ArrayList<String>()
    private val companions = ArrayList<ClassD>()
    /** Class name -> (companion object name, companion member names). */
    private val companionOf = HashMap<String, Pair<String, Set<String>>>()
    private var curClass: String? = null
    /** Object being parsed (members of objects see no [curClass]). */
    private var curObject: String? = null

    private fun objectDecl(name: String): ClassD {
        val line = t[p - 1].line
        val supers = ArrayList<String>(); var superArgs: List<E>? = null
        if (isOp(":")) {
            next()
            while (true) {
                val sn = ident(); superTypeArgs(sn)
                if (isOp("(")) {
                    if (superArgs != null) fail("only one superclass is allowed")
                    next()
                    superArgs = nested(1) { val xs = ArrayList<E>(); while (!isOp(")")) { xs.add(expr()); if (isOp(",")) next() }; xs }
                    expectOp(")")
                    supers.add(0, sn)
                } else supers.add(sn)
                if (isOp(",")) next() else break
            }
        }
        val fields = ArrayList<FieldD>(); val inits = ArrayList<Any>()
        val sta = takeSuperArgs()
        val saved = curClass; curClass = null
        val savedObj = curObject; curObject = name
        val ms = try { braceOnNextLine(); if (isOp("{")) members(fields, inits) else emptyList() } finally { curClass = saved; curObject = savedObj }
        objects.add(name)
        // During its own initialization the object's global is not assigned yet: `Obj.x` in initializers means `this.x`.
        val self = Subst(emptyMap(), rw = { x: E -> if (x is NameE && x.n == name) NameE("this").also { it.line = x.line } else null })
        val remap = fields.associateWith { f -> FieldD(f.name, f.type, f.isVar, f.init?.let { self.e(it) }, f.lateinit) }
        val inits2 = inits.map { i -> when (i) { is FieldD -> remap[i] ?: i; is Block -> self.block(i); else -> i } }
        return ClassD(name, fields.map { remap.getValue(it) }, supers, ms, line, emptyList(), inits2, superArgs, superTypeArgs = sta)
    }

    /** `object : Supers { body }` as an expression: a fresh local class, instantiated in place. */
    private fun anonObject(line: Int): E {
        val name = "\$anon${anonN++}"
        val supers = ArrayList<String>(); var superArgs: List<E>? = null
        if (isOp(":")) {
            next()
            while (true) {
                val sn = ident(); superTypeArgs(sn)
                if (isOp("(")) {
                    if (superArgs != null) fail("only one superclass is allowed")
                    next()
                    superArgs = nested(1) { val xs = ArrayList<E>(); while (!isOp(")")) { xs.add(expr()); if (isOp(",")) next() }; xs }
                    expectOp(")")
                    supers.add(0, sn)
                } else supers.add(sn)
                if (isOp(",")) next() else break
            }
        }
        // Inside its own class body, names would resolve to the outer instance's (also private) members.
        if (curClass != null && curClass in supers) fail("anonymous objects extending their enclosing class are not supported")
        val fields = ArrayList<FieldD>(); val inits = ArrayList<Any>()
        val sta = takeSuperArgs()
        val saved = curClass; curClass = null
        val outerDecls = pendingDecls; pendingDecls = ArrayList(); val lvl = bodyLevel; bodyLevel = 0
        val ms = try { nested(0) { if (isOp("{")) members(fields, inits) else emptyList() } } finally { curClass = saved; pendingDecls = outerDecls; bodyLevel = lvl }
        val c = ClassD(name, fields, supers, ms, line, emptyList(), inits, superArgs, superTypeArgs = sta)
        if (bodyLevel > 0) { pendingDecls.add(LocalDeclS(c).also { it.line = line }); sawLocal = true } else topAnon.add(c)
        return CallE(NameE(name).also { it.line = line }, emptyList(), emptyList(), null).also { it.line = line }
    }

    /** Enum entry ("E$A") -> hidden subclass holding its body. */
    private val enumBodies = HashMap<String, String>()

    /** Enum entries become top-level immortal instances `E$A` (initialized first, in order) plus the list `E$values`. */
    private fun enumGlobals(classes: List<ClassD>): List<VarS> {
        val gs = ArrayList<VarS>()
        for (c in classes) {
            val es = c.enumEntries ?: continue
            es.forEachIndexed { i, (n, args) ->
                val call = CallE(NameE(enumBodies["${c.name}$$n"] ?: c.name), emptyList(), listOf(StrL(listOf(n)), IntL(i.toLong(), false)) + args, null).also { it.line = c.line }
                gs.add(VarS("${c.name}$$n", false, TypeRef(c.name, emptyList(), false), call).also { it.line = c.line })
            }
            val list = CallE(NameE("arrayListOf"), listOf(TypeRef(c.name, emptyList(), false)), es.map { NameE("${c.name}$${it.first}") }, null).also { it.line = c.line }
            gs.add(VarS("${c.name}\$values", false, null, list).also { it.line = c.line })
        }
        return gs
    }

    private fun members(props: MutableList<FieldD>? = null, inits: MutableList<Any>? = null, open: Boolean = true): List<FunD> {
        val ms = ArrayList<FunD>()
        if (open) expectOp("{")
        nested(0) {
            while (true) {
                skipNL()
                if (isOp("}")) break
                var isConst = false; var isLate = false
                var isPrivate = false; var isAbstract = false
                if (isContextDecl()) { contextDecl(); continue }
                if (pendingCtx != null && !isId("fun") && !isId("val") && !isId("var") && !(peek().k == K.ID && peek().s in MEMBER_MODS)) noCtx()
                while (peek().k == K.ID && peek().s in MEMBER_MODS && t[p + 1].k == K.ID) { if (peek().s == "const") isConst = true; if (peek().s == "lateinit") isLate = true; if (peek().s == "private") isPrivate = true; if (peek().s == "abstract") isAbstract = true; p++ }
                if (pendingCtx != null && (isId("val") || isId("var"))) { ctxMemberProp(ms, props == null || isAbstract); continue }
                if (isPrivate && curClass != null && (isId("val") || isId("var") || isId("fun")) && t[p + 1].k == K.ID) privateMembers.add(curClass!! to t[p + 1].s)
                if (isPrivate && curIface != null && isId("fun") && t[p + 1].k == K.ID) privateIfaceFuns.getOrPut(curIface!!) { HashSet() }.add(t[p + 1].s)
                if (props != null && inits != null && (isId("val") || isId("var"))) {
                    val isVar = next().s == "var"
                    val n = ident()
                    val ty = if (isOp(":")) { next(); type() } else null
                    if (isId("by")) { val (df, acc) = delegated(n, ty, isVar, NameE("this"), "\$prop\$$n", "\$set\$$n"); props.add(df); inits.add(df); ms.addAll(acc); continue }
                    getter("\$prop\$$n", ty, emptyList(), null)?.let { ms.add(it); continue }
                    // `abstract val x: T` in a class: an abstract getter, like an interface property.
                    if (isAbstract && !isVar && ty != null && !isOp("=")) { ms.add(FunD("\$prop\$$n", emptyList(), ty, null, null, peek().line)); continue }
                    // Without an initializer (`lateinit var x: T`, or assigned in an `init` block) the property starts at
                    // the type's zero value (null for references) and is assignable during construction.
                    val f = if (!isOp("=")) {
                        if (ty == null) fail("body properties need an initializer")
                        FieldD(n, ty, true, zeroOf(ty), isLate)
                    } else { next(); skipNLOnly(); FieldD(n, ty, isVar, expr()) }
                    accessors(n, ty, f, isVar)?.let { (bf, acc) -> props.add(bf); inits.add(bf); ms.addAll(acc); continue }
                    props.add(f); inits.add(f)
                    val constOwner = curClass ?: curObject
                    if (isConst && constOwner != null && (f.init is IntL || f.init is BoolL || f.init is CharL || f.init is DblL || (f.init is StrL && (f.init as StrL).parts.all { it is String })))
                        { constVals["$constOwner.$n"] = f.init!!; if ('$' in constOwner) constVals["${constOwner.substringBefore('$')}.$n"] = f.init!! }
                    continue
                }
                if (props == null && (isId("val") || isId("var"))) {
                    // Interface property: an abstract getter `$prop$name` (or a default getter).
                    val line = next().line
                    val n = ident()
                    if (!isOp(":")) fail("interface properties need a type")
                    next(); val ty = type()
                    ms.add(getter("\$prop\$$n", ty, emptyList(), null) ?: FunD("\$prop\$$n", emptyList(), ty, null, null, line))
                    continue
                }
                if (inits != null && isId("init")) { next(); skipNLOnly(); inits.add(block()); continue }
                if (isId("companion") && (t[p + 1].k == K.ID && t[p + 1].s == "object" || t[p + 1].k == K.OP && t[p + 1].s == "{")) {
                    val cls = curClass ?: curIface ?: fail("companion objects are only supported in classes and interfaces")
                    if (companionOf.containsKey(cls)) fail("multiple companion declarations are not supported")
                    // Enum companion blocks would need the entries initialized first.
                    if ((t[p + 1].k == K.OP && t[p + 1].s == "{") && cls in enumClasses) fail("companion blocks in enum classes are not supported")
                    // Companion blocks initialize supertypes' companion blocks first; not modeled.
                    if ((t[p + 1].k == K.OP && t[p + 1].s == "{") && (headerSupers[cls].orEmpty().isNotEmpty() || ifaceSupers[cls].orEmpty().isNotEmpty()))
                        fail("companion blocks in classes with supertypes are not supported")
                    next(); if (isId("object")) next()
                    val cn = if (peek().k == K.ID) ident() else "Companion"
                    val od = objectDecl("$cls$$cn")
                    companions.add(od)
                    companionOf[cls] = od.name to (od.fields.map { it.name } + od.methods.map { it.name }).toSet() + cn
                    continue
                }
                if (nestedDecl()) continue
                if (isId("constructor") && curClass != null && props != null) { secondaryCtor(); continue }
                if (!isId("fun")) fail("only member functions are supported in class/interface bodies")
                val f = function()
                if (f.recv != null) {
                    // Member extension `fun R.f(...)`: method `mext$f($this_ext: R, ...)`; `this` is the extension receiver.
                    if (curClass == null && curObject == null && curIface == null) fail("member extension functions are only supported in classes, objects and interfaces")
                    if (f.typeParams.isNotEmpty()) fail("generic member extension functions are not supported")
                    // Unqualified calls would bind to the dispatch receiver before the extension receiver.
                    if (f.recv.name in memberTps) fail("member extensions on a type parameter are not supported")
                    if (f.name in setOf("unaryPlus", "unaryMinus", "not", "inc", "dec", "plus", "minus", "times", "div", "rem", "get", "set", "invoke",
                            "contains", "compareTo", "rangeTo", "rangeUntil", "iterator", "next", "hasNext", "equals", "getValue", "setValue", "provideDelegate")
                        || f.name.endsWith("Assign") || f.name.startsWith("component")) fail("operator member extension functions are not supported")
                    val sub = Subst(emptyMap(), rw = { x -> if (x is NameE && x.n == "this") NameE("\$this_ext").also { it.line = x.line } else null })
                    ms.add(FunD("mext\$${f.name}", listOf(Param("\$this_ext", f.recv)) + f.params, f.ret, f.body?.let { sub.block(it) },
                        f.exprBody?.let { sub.e(it) }, f.line, emptyList(), f.recv))
                    continue
                }
                ms.add(f)
            }
        }
        expectOp("}")
        return ms
    }

    /**
     * A class implementing an interface property with a stored property (own or inherited) gets the getter
     * `$prop$name() = name`, so calls through the interface dispatch to the field.
     */
    /** Interface name -> directly declared super-interfaces. */
    private val ifaceSupers = HashMap<String, List<String>>()

    /**
     * Interface inheritance: each interface gets its ancestors' methods and defaults (unless redeclared) and records
     * all ancestors; classes implementing an interface also implement its ancestors.
     */
    private fun flattenIfaces(classes: MutableList<ClassD>, ifaces: MutableList<InterfaceD>) {
        if (ifaceSupers.isEmpty()) return
        val byName = ifaces.associateBy { it.name }
        fun anc(n: String, seen: MutableSet<String> = LinkedHashSet()): Set<String> {
            for (s in ifaceSupers[n].orEmpty()) { if (s !in byName) fail("unknown interface $s"); if (seen.add(s)) anc(s, seen) }
            return seen
        }
        val ancestors = ifaces.associate { it.name to anc(it.name).toList() }
        ifaceAnc.putAll(ancestors)
        for (i in ifaces.indices) {
            val d = ifaces[i]; val a = ancestors.getValue(d.name)
            if (a.isEmpty()) continue
            val ms = ArrayList(d.methods)
            val defs = ArrayList(ifaceDefaults[d.name].orEmpty())
            for (s in a) {
                for (m in byName.getValue(s).methods) if (ms.none { it.name == m.name }) ms.add(m)
                if (s in genericIfaceTps) fail("default methods inherited from a generic super-interface are not supported")
            }
            // Inherited defaults: the most specific declaration wins (`interface D : B, C` with `C : A` overriding A's).
            for (m in mostSpecificDefaults(a)) if (defs.none { it.name == m.name } && d.methods.none { it.name == m.name }) defs.add(m)
            if (defs.isNotEmpty()) ifaceDefaults[d.name] = defs
            ifaces[i] = InterfaceD(d.name, ms, d.line, d.typeParams, a)
        }
        for (i in classes.indices) {
            val c = classes[i]
            val extra = c.supers.flatMap { ancestors[it].orEmpty() }.filter { it !in c.supers }.distinct()
            if (extra.isNotEmpty()) classes[i] = ClassD(c.name, c.fields, c.supers + extra, c.methods, c.line, c.ctorParams, c.inits, c.superArgs, c.typeParams, c.enumEntries, c.isValue, c.superTypeArgs)
        }
    }

    /** Class name -> interfaces implemented by delegation (`: I by expr`). */
    /** Set while parsing a `by` delegate in a class header: `{` starts the class body, not a trailing lambda. */
    private var noBraceCall = false
    /** Interface whose body is being parsed (for its companion object). */
    private var curIface: String? = null
    private val enumClasses = HashSet<String>()
    /** A parameter default; trailing lambdas there are re-synthesized per call site, which the backend does not support. */
    private fun defaultArg(): E {
        val st = p
        val x = expr()
        for (k in st + 1 until p) if (t[k].k == K.OP && t[k].s == "{" && (t[k - 1].k == K.ID || t[k - 1].k == K.OP && t[k - 1].s == ")"))
            fail("trailing lambdas in default arguments are not supported", t[k])
        return x
    }
    private val headerSupers = HashMap<String, List<String>>()
    private val byDelegs = HashMap<String, MutableList<String>>()

    /** Interface name -> its default method bodies. */
    private val ifaceDefaults = HashMap<String, List<FunD>>()
    /** Interface -> all its super-interfaces. */
    private val ifaceAnc = HashMap<String, List<String>>()
    /** Interface that declares each default body (defaults are shared by reference with sub-interfaces). */
    private val defaultOwner = HashMap<FunD, String>()

    /**
     * Default methods visible through the interfaces [ss]: for each name, the declarations whose interface is not a
     * super-interface of another declaring interface (Kotlin's most specific implementation).
     */
    private fun mostSpecificDefaults(ss: List<String>): List<FunD> {
        val cands = LinkedHashSet<FunD>()
        for (s in ss) cands.addAll(ifaceDefaults[s].orEmpty())
        return cands.filter { m ->
            val o = defaultOwner[m] ?: return@filter true
            cands.none { x -> x !== m && x.name == m.name && defaultOwner[x]?.let { ox -> o in ifaceAnc[ox].orEmpty() } == true }
        }
    }
    /** Generic interfaces with default methods -> their type parameters (copies are substituted per implementation). */
    private val genericIfaceTps = HashMap<String, List<String>>()

    private fun implementIfaceProps(classes: MutableList<ClassD>, ifaces: List<InterfaceD>) {
        val byName = classes.associateBy { it.name }
        fun chain(c: ClassD): List<ClassD> = generateSequence(c) { x -> x.superClass?.let { byName[it] } }.toList()
        for (i in classes.indices) {
            val c = classes[i]
            val ch = chain(c)
            val props = ch.flatMap { it.supers }.mapNotNull { s -> ifaces.firstOrNull { it.name == s } }
                .flatMap { it.methods }.filter { it.name.startsWith("\$prop\$") && it.params.isEmpty() }
            val add = ArrayList<FunD>()
            for (m in props) {
                val n = m.name.removePrefix("\$prop\$")
                if (ch.any { x -> x.methods.any { it.name == m.name } } || add.any { it.name == m.name }) continue
                if (ch.none { x -> x.fields.any { it.name == n } }) continue
                add.add(FunD(m.name, emptyList(), m.ret, null, NameE(n).also { it.line = c.line }, c.line))
            }
            // `abstract val x: T` of a superclass overridden by a stored property: the getter returns the field.
            for (m in ch.drop(1).flatMap { it.methods }) {
                if (!m.name.startsWith("\$prop\$") || m.params.isNotEmpty() || m.body != null || m.exprBody != null) continue
                val n = m.name.removePrefix("\$prop\$")
                if (ch.any { x -> x.methods.any { it.name == m.name && (it.body != null || it.exprBody != null) } } || add.any { it.name == m.name }) continue
                val fd = ch.firstNotNullOfOrNull { x -> x.fields.firstOrNull { it.name == n } } ?: continue
                add.add(FunD(m.name, emptyList(), fd.type ?: m.ret, null, NameE(n).also { it.line = c.line }, c.line))
            }
            for (s in byDelegs[c.name].orEmpty()) {
                val iface = ifaces.firstOrNull { it.name == s } ?: fail("only interfaces can be delegated with 'by'")
                val targs = c.superTypeArgs[s].orEmpty()
                val tmap = iface.typeParams.zip(targs).toMap()
                fun st(t: TypeRef): TypeRef = tmap[t.name]?.let { r -> if (t.nullable && !r.nullable) TypeRef(r.name, r.args, true) else r }
                    ?: TypeRef(t.name, t.args.map { st(it) }, t.nullable)
                if (iface.typeParams.isNotEmpty() && targs.size != iface.typeParams.size) fail("delegated generic interface needs type arguments")
                val recv = NameE("\$by\$$s")
                for (m in iface.methods) {
                    if (ch.any { x -> x.methods.any { it.name == m.name && (it.body != null || it.exprBody != null) } } || add.any { it.name == m.name }) continue
                    if (m.typeParams.isNotEmpty()) fail("delegating generic interface methods is not supported")
                    val ps = m.params.map { Param(it.name, st(it.type), it.default, it.vararg, it.ctx) }
                    if (m.params.any { it.vararg }) fail("delegating vararg methods is not supported")
                    val body: E = if (m.name.startsWith("\$prop\$") && m.params.isEmpty()) MemberE(recv, m.name.removePrefix("\$prop\$"), false)
                        else CallE(MemberE(recv, m.name, false), emptyList(), ps.map { NameE(it.name) }, null)
                    body.line = c.line
                    add.add(FunD(m.name, ps, st(m.ret ?: TypeRef("Unit", emptyList(), false)), null, body, c.line))
                }
            }
            val chainSupers = ch.flatMap { it.supers }.distinct()
            val chosen = mostSpecificDefaults(chainSupers.filter { it in ifaceDefaults || it in ifaceAnc }).toSet()
            for (s in chainSupers) for (d in ifaceDefaults[s].orEmpty()) {
                if (d !in chosen) continue
                if (ch.any { x -> x.methods.any { it.name == d.name && (it.body != null || it.exprBody != null) } } || add.any { it.name == d.name }) continue
                // Only the topmost class in the chain needs the copy; subclasses inherit it.
                val top = ch.last { x -> s in x.supers }
                if (top !== c) continue
                val copy = FunD(d.name, d.params, d.ret ?: TypeRef("Unit", emptyList(), false), d.body, d.exprBody, d.line, d.typeParams)
                val gtps = genericIfaceTps[s]
                if (gtps == null) { add.add(copy); continue }
                val targs = top.superTypeArgs[s] ?: fail("generic interface $s needs type arguments in ${top.name}")
                if (targs.size != gtps.size) fail("wrong number of type arguments for $s")
                add.add(Subst(gtps.zip(targs).toMap()).fn(copy))
            }
            if (add.isNotEmpty()) classes[i] = ClassD(c.name, c.fields, c.supers, c.methods + add, c.line, c.ctorParams, c.inits, c.superArgs, c.typeParams, c.enumEntries, c.isValue, c.superTypeArgs)
        }
    }

    private fun zeroOf(t: TypeRef): E = if (t.nullable) NullL() else when (t.name) {
        "Int", "Short", "Byte" -> IntL(0, false); "Long" -> IntL(0, true); "UInt" -> IntL(0, false, true); "ULong" -> IntL(0, true, true)
        "Double" -> DblL(0.0); "Float" -> DblL(0.0, true); "Boolean" -> BoolL(false); "Char" -> CharL(0); else -> NullL()
    }

    /** `const val`s of objects and companions with literal values ("Obj.name" -> literal); uses do not initialize the object. */
    private val constVals = HashMap<String, E>()

    private fun inlineConsts(funs: MutableList<FunD>, classes: MutableList<ClassD>, globals: MutableList<VarS>) {
        if (constVals.isEmpty()) return
        val sub = Subst(emptyMap(), rw = { x -> if (x is MemberE && x.recv is NameE) constVals["${x.recv.n}.${x.n}"] else null })
        for (i in funs.indices) funs[i] = keepRecv(sub.fn(funs[i]), funs[i])
        for (i in globals.indices) globals[i] = sub.s(globals[i]) as VarS
        for (i in classes.indices) {
            val c = classes[i]
            val fields = c.fields.map { FieldD(it.name, it.type, it.isVar, it.init?.let { e -> sub.e(e) }, it.lateinit) }
            val inits = c.inits.map { if (it is FieldD) fields.first { f -> f.name == it.name } else sub.block(it as Block) }
            classes[i] = ClassD(c.name, fields, c.supers, c.methods.map { keepRecv(sub.fn(it), it) }, c.line, c.ctorParams, inits, c.superArgs?.map { sub.e(it) }, c.typeParams,
                c.enumEntries?.map { (n, a) -> n to a.map { sub.e(it) } }, c.isValue, c.superTypeArgs)
        }
    }

    /** Classes, objects and interfaces declared inside class bodies, hoisted to the top level under their simple names. */
    private val hoisted = ArrayList<ClassD>()
    private val hoistedIfaces = ArrayList<InterfaceD>()
    /** Nested type name -> enclosing class. */
    private val nestedIn = HashMap<String, String>()
    /** Inner class name -> enclosing class (instances hold the outer instance in `outer$`). */
    private val innerOf = HashMap<String, String>()

    /** A nested or inner declaration in a class body; false if the next member is not one. */
    private fun nestedDecl(): Boolean {
        val outer = curClass
        val save = p
        val inner = isId("inner") && t[p + 1].k == K.ID
        if (inner) next()
        val d: Any = when {
            isId("class") -> classDecl()
            isId("data") && t[p + 1].k == K.ID && t[p + 1].s == "class" -> { next(); dataClass(classDecl()) }
            isId("enum") && t[p + 1].k == K.ID && t[p + 1].s == "class" -> { next(); classDecl(true) }
            !inner && isId("object") && t[p + 1].k == K.ID -> { next(); objectDecl(ident()) }
            !inner && isId("interface") -> interfaceDecl()
            else -> { p = save; return false }
        }
        if (outer == null) fail("nested declarations are only supported in classes", t[save])
        val name = if (d is ClassD) d.name else (d as InterfaceD).name
        if (name in nestedIn) fail("two nested classes named $name are not supported", t[save])
        nestedIn[name] = outer
        if (inner) innerOf[name] = outer
        if (d is ClassD) hoisted.add(d) else hoistedIfaces.add(d as InterfaceD)
        return true
    }

    /**
     * Resolves nested and inner classes after parsing: `Outer.Nested` becomes `Nested`; inner classes get an `outer$`
     * first property, their uses of the outer class's members go through it, and their constructor calls pass it.
     */
    private fun resolveNested(funs: MutableList<FunD>, classes: MutableList<ClassD>, ifaces: MutableList<InterfaceD>, globals: MutableList<VarS>) {
        if (nestedIn.isEmpty() && innerOf.isEmpty()) return
        val byName = classes.associateBy { it.name }
        val qualified = nestedIn.entries.associate { (n, o) -> "$o.$n" to TypeRef(n, emptyList(), false) }
        for (o in innerOf.values) if (byName[o]?.typeParams?.isNotEmpty() == true) fail("inner classes of generic classes are not supported")
        // An inner class extending its outer class (or one of its ancestors): an unqualified member name could mean the
        // inherited member or the outer instance's one; that is not told apart (qualified `this.x` / `this@Outer.x` is fine).
        for ((ic, o) in innerOf) {
            val outerChain = generateSequence(byName[o]) { c -> c.superClass?.let { byName[it] } }.toList()
            val chainNames = outerChain.map { it.name }.toSet()
            val icd = byName[ic] ?: continue
            if (generateSequence(icd.superClass) { s -> byName[s]?.superClass }.none { it in chainNames }) continue
            val members = outerChain.flatMap { c -> c.fields.map { it.name } + c.methods.map { it.name.removePrefix("\$prop\$") } + c.ctorParams.map { it.name } }.toSet() + innerOf.filterValues { it in chainNames }.keys
            val used = HashSet<String>(); localDeclNames(icd, used)
            if ((used - ownNames(icd)).any { it in members }) fail("inner class $ic extending its outer class is not supported")
        }
        fun names(c: ClassD) = c.fields.map { it.name }.toSet() + c.methods.map { it.name.removePrefix("\$prop\$") }
        fun locals(f: FunD): Set<String> {
            val out = HashSet<String>(f.params.map { it.name })
            Subst(emptyMap(), srw = { x -> if (x is VarS) out.add(x.name); if (x is ForS) out.add(x.name); null }, rw = { x -> if (x is LambdaE) out.addAll(x.body.params); null }).fn(f)
            return out
        }
        // ctx: class whose members are being rewritten (null for top-level functions).
        fun rewriter(ctx: String?, f: FunD?): Subst {
            val own = ctx?.let { byName[it] }?.let { names(it) } ?: emptySet()
            val outerName = ctx?.let { innerOf[it] }
            val outerNames = outerName?.let { byName[it] }?.let { names(it) }?.minus(own)?.minus(f?.let { locals(it) } ?: emptySet()) ?: emptySet()
            fun outerRef(line: Int) = NameE("outer\$").also { it.line = line }
            lateinit var sub: Subst
            sub = Subst(qualified, rw = { x ->
                when {
                    x is MemberE && x.recv is NameE && (x.recv.n in byName || x.recv.n in nestedIn) && nestedIn[x.n] == x.recv.n -> NameE(x.n).also { it.line = x.line }
                    x is NameE && x.n.startsWith("this@") -> {
                        val l = x.n.removePrefix("this@")
                        NameE(if (l == ctx) "this" else if (l == outerName) "outer\$" else fail("unknown label this@$l")).also { it.line = x.line }
                    }
                    x is NameE && x.n in outerNames -> MemberE(outerRef(x.line), x.n, false).also { it.line = x.line }
                    x is CallE && x.callee is NameE && x.callee.n in innerOf -> {
                        val o = innerOf.getValue(x.callee.n)
                        val ref: E = if (o == ctx) NameE("this").also { it.line = x.line } else if (o == outerName) outerRef(x.line) else fail("inner class ${x.callee.n} needs an outer instance")
                        CallE(x.callee, x.typeArgs, listOf(ref) + x.args.map { sub.e(it) }, x.lambda?.let { sub.block(it) }).also { it.line = x.line }
                    }
                    x is CallE && x.callee is MemberE && x.callee.n in innerOf && x.callee.safe -> fail("safe call of an inner class constructor is not supported")
                    x is CallE && x.callee is MemberE && x.callee.n in innerOf && !(x.callee.recv is NameE && x.callee.recv.n in byName) ->
                        CallE(NameE(x.callee.n).also { it.line = x.line }, x.typeArgs, listOf(sub.e(x.callee.recv)) + x.args.map { sub.e(it) }, x.lambda?.let { sub.block(it) }).also { it.line = x.line }
                    x is CallE && x.callee is NameE && x.callee.n in outerNames ->
                        CallE(MemberE(outerRef(x.line), x.callee.n, false).also { it.line = x.line }, x.typeArgs, x.args.map { sub.e(it) }, x.lambda?.let { sub.block(it) }).also { it.line = x.line }
                    else -> null
                }
            })
            return sub
        }
        for (i in ifaces.indices) ifaces[i] = ifaces[i].let { d -> InterfaceD(d.name, d.methods.map { keepRecv(rewriter(null, it).fn(it), it) }, d.line, d.typeParams, d.supers) }
        for (i in globals.indices) globals[i] = rewriter(null, null).s(globals[i]) as VarS
        for (i in funs.indices) funs[i] = keepRecv(rewriter(null, funs[i]).fn(funs[i]), funs[i])
        for (i in classes.indices) {
            val c = classes[i]
            val ms = c.methods.map { keepRecv(rewriter(c.name, it).fn(it), it) }
            val sub = rewriter(c.name, null)
            val fields = c.fields.map { FieldD(it.name, it.type?.let { t -> sub.ty(t) }, it.isVar, it.init?.let { e -> sub.e(e) }, it.lateinit) }
            val inits = c.inits.map { if (it is FieldD) fields.first { f -> f.name == it.name } else sub.block(it as Block) }
            val o = innerOf[c.name]
            val of = if (o != null) listOf(FieldD("outer\$", TypeRef(o, emptyList(), false), false)) else emptyList()
            val op = if (o != null) listOf(Param("outer\$", TypeRef(o, emptyList(), false))) else emptyList()
            classes[i] = ClassD(c.name, of + fields, c.supers.map { qualified[it]?.name ?: it }, ms, c.line, op + c.ctorParams.map { Param(it.name, sub.ty(it.type), it.default?.let { d -> sub.e(d) }) }, inits, c.superArgs?.map { sub.e(it) }, c.typeParams,
                c.enumEntries?.map { (n, a) -> n to a.map { sub.e(it) } }, c.isValue, c.superTypeArgs)
        }
    }
    private val OPERATOR_NAMES = setOf("plus", "minus", "times", "div", "rem", "mod", "rangeTo", "rangeUntil", "compareTo", "equals",
        "contains", "get", "set", "invoke", "inc", "dec", "not", "unaryMinus", "unaryPlus", "plusAssign", "minusAssign",
        "timesAssign", "divAssign", "remAssign", "iterator", "hasNext", "next", "getValue", "setValue", "provideDelegate")

    /** Set when the parser saw a local declaration; [liftLocals] does nothing otherwise. */
    private var sawLocal = false
    /** Counter for fresh names of lifted local declarations. */
    private var localN = 0

    /** Names a function declares: parameters, local `val`/`var`s, `for` variables and lambda parameters. */
    private fun localsOf(f: FunD): Set<String> {
        val out = HashSet<String>(f.params.map { it.name })
        Subst(emptyMap(), srw = { x -> if (x is VarS) out.add(x.name); if (x is ForS) out.add(x.name); null },
            rw = { x -> if (x is LambdaE) out.addAll(x.body.params); null }).fn(f)
        return out
    }

    /** Declared or literal-evident type of the parameter or local `val` [n] of [f]; null if unknown. */
    private fun localType(f: FunD, n: String): TypeRef? {
        f.params.firstOrNull { it.name == n }?.let { return it.type }
        var t: TypeRef? = null; var seen = 0
        fun r(x: String) = TypeRef(x, emptyList(), false)
        Subst(emptyMap(), srw = { x ->
            if (x is VarS && x.name == n) {
                seen++
                t = x.type ?: when (val i = x.init) {
                    is StrL -> r("String"); is BoolL -> r("Boolean"); is CharL -> r("Char"); is DblL -> r(if (i.f32) "Float" else "Double")
                    is IntL -> if (i.unsigned) null else r(if (i.long) "Long" else "Int")
                    else -> null
                }
            }
            null
        }).fn(f)
        return if (seen == 1) t else null
    }

    /** Names used in [f] (also as callees and `::name` references), including inside its local declarations. */
    private fun usedNames(f: FunD, out: MutableSet<String> = HashSet()): MutableSet<String> {
        lateinit var sub: Subst
        sub = Subst(emptyMap(), rw = { x ->
            if (x is NameE) out.add(x.n)
            if (x is UnE && x.op.startsWith("::")) out.add(x.op.drop(2))
            null
        }, srw = { x -> if (x is LocalDeclS) localDeclNames(x.d, out); null })
        sub.fn(f)
        return out
    }

    private fun localDeclNames(d: Any, out: MutableSet<String>) {
        when (d) {
            is FunD -> usedNames(d, out)
            is InterfaceD -> d.methods.forEach { usedNames(it, out) }
            is ClassD -> {
                d.methods.forEach { usedNames(it, out) }
                val sub = Subst(emptyMap(), rw = { x -> if (x is NameE) out.add(x.n); null })
                d.fields.forEach { it.init?.let { e -> sub.e(e) } }
                d.inits.forEach { if (it is Block) sub.block(it) }
                d.superArgs?.forEach { sub.e(it) }
                d.ctorParams.forEach { it.default?.let { e -> sub.e(e) } }
            }
        }
    }

    /** Names assigned anywhere in [f] (`x = ...`, `x += ...`, `x++`), including in its local declarations. */
    private fun assignedNames(f: FunD, out: MutableSet<String> = HashSet()): MutableSet<String> {
        Subst(emptyMap(), rw = { x -> if (x is PostIncE && x.target is NameE) out.add(x.target.n); null }, srw = { x ->
            when {
                x is AssignS && x.target is NameE -> out.add(x.target.n)
                x is IncS && x.target is NameE -> out.add(x.target.n)
                x is LocalDeclS && x.d is FunD -> assignedNames(x.d, out)
            }
            null
        }).fn(f)
        return out
    }

    /** Names a class declares itself (properties, constructor parameters, methods and their locals). */
    private fun ownNames(c: ClassD): Set<String> =
        c.fields.map { it.name }.toSet() + c.ctorParams.map { it.name } + c.methods.flatMap { listOf(it.name.removePrefix("\$prop\$")) + localsOf(it) } + c.name

    /**
     * Lifts local declarations ([LocalDeclS]) out of function and method bodies:
     * - a local function that captures no local variable becomes a top-level function (inside a method: a method of
     *   the same class), renamed when its name is taken; calls and `::f` references in the enclosing body follow;
     * - a local function that captures local variables becomes a local `val` holding a lambda (closures capture values
     *   and box captured `var`s, see `boxCapturedVars`); such a function cannot be recursive, generic, an extension,
     *   or have default or vararg parameters;
     * - local classes, objects and interfaces become top-level declarations (renamed when the name is taken); they must
     *   not capture local variables.
     */
    private fun liftLocals(funs: MutableList<FunD>, classes: MutableList<ClassD>, ifaces: MutableList<InterfaceD>) {
        if (!sawLocal) return
        val taken = HashSet<String>()
        funs.forEach { taken.add(it.name) }; classes.forEach { taken.add(it.name) }; ifaces.forEach { taken.add(it.name) }
        fun fresh(n: String, extra: Set<String> = emptySet()): String {
            if (n !in taken && n !in extra) { taken.add(n); LiftedLocals.names.add(n); return n }
            while (true) { val m = "$n\$l${localN++}"; if (m !in taken && m !in extra) { taken.add(m); LiftedLocals.names.add(m); return m } }
        }
        fun copyFun(n: FunD, o: FunD, name: String = n.name) = FunD(name, n.params, n.ret, n.body, n.exprBody, n.line, o.typeParams, o.recv)
        fun copyClass(sub: Subst, c: ClassD, name: String): ClassD {
            val fm = c.fields.associateWith { f -> FieldD(f.name, f.type?.let { sub.ty(it) }, f.isVar, f.init?.let { sub.e(it) }, f.lateinit) }
            return ClassD(name, c.fields.map { fm.getValue(it) }, c.supers.map { sub.ty(TypeRef(it, emptyList(), false)).name }, c.methods.map { copyFun(sub.fn(it), it) }, c.line,
                c.ctorParams.map { Param(it.name, sub.ty(it.type), it.default?.let { d -> sub.e(d) }, it.vararg) },
                c.inits.map { if (it is FieldD) fm[it] ?: it else sub.block(it as Block) }, c.superArgs?.map { sub.e(it) }, c.typeParams,
                c.enumEntries?.map { (n, a) -> n to a.map { sub.e(it) } }, c.isValue, c.superTypeArgs)
        }
        // Lifts the local declarations directly inside [f0]; returns the rewritten function. [owner]: class of a method.
        fun lift(f0: FunD, owner: ClassD?, newMethods: MutableList<FunD>): FunD {
            var f = f0
            while (true) {
                val decls = ArrayList<Any>()
                Subst(emptyMap(), srw = { x -> if (x is LocalDeclS) decls.add(x.d); null }).fn(f)
                if (decls.isEmpty()) return f
                val outer = localsOf(f)
                val assigned = assignedNames(f)
                // A local `var` captured by a local class and assigned anywhere is shared through a `$Ref` cell:
                // `var x = e` -> `val x = $Ref(e)`, uses `x` -> `x.v`; the class then captures the cell by value.
                val refVars = HashMap<String, TypeRef>()
                for (d in decls) if (d is ClassD && d.enumEntries == null && d.superClass == null && d.methods.none { it.name == "\$equals" }) {
                    // (With a superclass, its constructor may call an override before the captured cell is stored.)
                    val used = HashSet<String>(); localDeclNames(d, used)
                    val assignedIn = HashSet<String>(); d.methods.forEach { assignedNames(it, assignedIn) }
                    for (c in used - ownNames(d)) if (c in outer && c !in f.params.map { it.name } && (c in assigned || c in assignedIn)) {
                        val t = localType(f, c) ?: continue
                        refVars[c] = t
                    }
                }
                if (refVars.isNotEmpty()) {
                    // Shadowing (lambda parameters, a local declaration's own names) would be rewritten too: not supported.
                    val lamParams = HashSet<String>()
                    Subst(emptyMap(), rw = { x -> when (x) { is LambdaE -> lamParams.addAll(x.body.params.ifEmpty { listOf("it") }); is CallE -> x.lambda?.let { lamParams.addAll(it.params.ifEmpty { listOf("it") }) }; else -> {} }; null }).fn(f)
                    refVars.keys.removeAll { it in lamParams || decls.any { d -> (d is ClassD && it in ownNames(d)) || (d is FunD && (it in localsOf(d) || d.params.any { p -> p.name == it })) || d is InterfaceD } }
                }
                if (refVars.isNotEmpty()) {
                    LiftedLocals.usesRef = true
                    lateinit var bx: Subst
                    bx = Subst(emptyMap(), rw = { x -> if (x is NameE && x.n in refVars) MemberE(NameE(x.n).also { it.line = x.line }, "v", false).also { it.line = x.line } else null }, srw = { x ->
                        when {
                            x is VarS && x.isVar && x.name in refVars -> {
                                val t = refVars.getValue(x.name)
                                VarS(x.name, false, TypeRef("\$Ref", listOf(t), false), CallE(NameE("\$Ref").also { it.line = x.line }, listOf(t), listOf(bx.e(x.init)), null).also { it.line = x.line }).also { it.line = x.line }
                            }
                            x is LocalDeclS && x.d is ClassD -> LocalDeclS(copyClass(bx, x.d, x.d.name)).also { it.line = x.line }
                            x is LocalDeclS && x.d is FunD -> LocalDeclS(copyFun(bx.fn(x.d), x.d)).also { it.line = x.line }
                            else -> null
                        }
                    })
                    f = copyFun(bx.fn(f), f)
                    continue
                }
                val rename = HashMap<String, String>()
                val types = HashMap<String, TypeRef>()
                val asLambda = HashSet<FunD>()
                val capCls = HashMap<String, List<Pair<String, TypeRef>>>()
                val ownerNames = owner?.let { o -> o.methods.map { it.name } + newMethods.map { it.name } }?.toSet() ?: emptySet()
                for (d in decls) when (d) {
                    is FunD -> {
                        // Operator resolution against built-in operators is not scoped: a local `Int?.plus` would
                        // change every `+` in the program.
                        if (d.recv != null && d.name in OPERATOR_NAMES)
                            throw CompileError("line ${d.line}: local operator extension functions are not supported")
                        val caps = usedNames(d) - localsOf(d) - d.name
                        if (caps.none { it in outer }) rename[d.name] = fresh(d.name, ownerNames)
                        else {
                            if (d.name in usedNames(d)) throw CompileError("line ${d.line}: recursive local functions that capture local variables are not supported")
                            if (d.typeParams.isNotEmpty() || d.recv != null || d.params.any { it.default != null || it.vararg })
                                throw CompileError("line ${d.line}: local functions that capture local variables cannot be generic, extensions or have default/vararg parameters")
                            asLambda.add(d)
                        }
                    }
                    is ClassD, is InterfaceD -> {
                        val used = HashSet<String>(); localDeclNames(d, used)
                        val own = if (d is ClassD) ownNames(d) else (d as InterfaceD).methods.flatMap { localsOf(it) + it.name }.toSet()
                        val caps = used - own
                        val capd = caps.filter { it in outer }.sorted()
                        if (capd.isNotEmpty()) {
                            fun no(): Nothing = throw CompileError("line ${if (d is ClassD) d.line else (d as InterfaceD).line}: local classes that capture local variables are not supported ('${capd.first()}')")
                            // Captured `val`s become leading constructor properties of the lifted class; every
                            // construction passes the current values. Captured `var`s, data classes, objects,
                            // interfaces and superclasses of other local classes are not supported.
                            // Assignments inside the class's own methods count too (a captured `var` must be shared, not copied).
                            val assignedIn = HashSet<String>(); if (d is ClassD) d.methods.forEach { assignedNames(it, assignedIn) }
                            if (d !is ClassD || capd.any { it in assigned || it in assignedIn } || d.methods.any { it.name == "\$equals" } || d.enumEntries != null) no()
                            if (decls.any { o -> o is ClassD && o !== d && d.name in o.supers }) no()
                            // A member inherited from a superclass would shadow the captured value: not supported.
                            fun inherited(c: ClassD, seen: Int = 0): Set<String> = if (seen > 20) emptySet() else
                                c.supers.flatMap { sn -> (classes + decls.filterIsInstance<ClassD>()).filter { it.name == sn }.flatMap { sc -> sc.fields.map { it.name } + sc.ctorParams.map { it.name } + sc.methods.map { it.name.removePrefix("\$prop\$") } + inherited(sc, seen + 1) } }.toSet()
                            val body = HashSet<String>(); localDeclNames(ClassD(d.name, d.fields, d.supers, d.methods, d.line, d.ctorParams, d.inits, null, d.typeParams), body)
                            if (capd.any { it in body && it in inherited(d) }) no()
                            capCls[d.name] = capd.map { c -> c to (localType(f, c) ?: no()) }
                        }
                        val n = if (d is ClassD) d.name else (d as InterfaceD).name
                        val nn = fresh(n)
                        if (nn != n) { rename[n] = nn; types[n] = TypeRef(nn, emptyList(), false) }
                    }
                }
                val inLambdaFns = ArrayList<Any>()
                for (d in asLambda) Subst(emptyMap(), srw = { x -> if (x is LocalDeclS) inLambdaFns.add(x.d); null }).fn(d)
                lateinit var sub: Subst
                sub = Subst(types, rw = { x ->
                    when {
                        x is CallE && x.callee is NameE && x.callee.n in capCls ->
                            CallE(NameE(rename[x.callee.n] ?: x.callee.n).also { it.line = x.callee.line }, x.typeArgs.map { sub.ty(it) },
                                capCls.getValue(x.callee.n).map { (c, _) -> NameE(c).also { it.line = x.line } } + x.args.map { sub.e(it) },
                                x.lambda?.let { sub.block(it) }).also { it.line = x.line }
                        x is NameE && x.n in rename -> NameE(rename.getValue(x.n)).also { it.line = x.line }
                        x is UnE && x.op.startsWith("::") && x.e is NullL && x.op.drop(2) in rename -> UnE("::" + rename.getValue(x.op.drop(2)), x.e).also { it.line = x.line }
                        else -> null
                    }
                }, srw = { x ->
                    if (x !is LocalDeclS) null
                    // Declarations nested deeper (inside a lifted local function) are lifted with that function.
                    // (Inside a local function turned into a lambda they are still dropped, as before.)
                    else if (decls.none { it === x.d } && inLambdaFns.none { it === x.d }) null
                    else if (x.d is FunD && x.d in asLambda) {
                        val g = x.d
                        val ret = g.ret ?: if (g.body != null) TypeRef("Unit", emptyList(), false) else null
                        val ty = ret?.let { TypeRef("\$Fn", g.params.map { it.type } + it, false) }
                        val body = g.body ?: Block(listOf(ExprS(g.exprBody!!).also { it.line = g.line }))
                        VarS(g.name, false, ty, LambdaE(sub.block(Block(body.stmts, g.params.map { it.name }, g.params.map { it.type }))).also { it.line = g.line }).also { it.line = g.line }
                    } else ExprS(BoolL(true)).also { it.line = x.line }
                })
                f = copyFun(sub.fn(f), f)
                for (d in decls) when (d) {
                    is FunD -> if (d !in asLambda) {
                        val g = copyFun(sub.fn(d), d, rename.getValue(d.name))
                        if (owner != null) newMethods.add(lift(g, owner, newMethods)) else funs.add(lift(g, null, newMethods))
                    }
                    is ClassD -> classes.add(copyClass(sub, d, rename[d.name] ?: d.name).let { c ->
                        val cs = capCls[d.name] ?: return@let c
                        // Inside the class the captured values are its hidden `$cap$x` properties.
                        val names = cs.map { it.first }.toSet()
                        val r = copyClass(Subst(emptyMap(), rw = { x -> if (x is NameE && x.n in names) NameE("\$cap\$" + x.n).also { it.line = x.line } else null }), c, c.name)
                        ClassD(r.name, cs.map { (n, t) -> FieldD("\$cap\$" + n, t, false) } + r.fields, r.supers, r.methods, r.line,
                            cs.map { (n, t) -> Param("\$cap\$" + n, t) } + r.ctorParams, r.inits, r.superArgs, r.typeParams, r.enumEntries, r.isValue, r.superTypeArgs)
                    })
                    is InterfaceD -> ifaces.add(InterfaceD(rename[d.name] ?: d.name, d.methods.map { copyFun(sub.fn(it), it) }, d.line, d.typeParams, d.supers))
                }
            }
        }
        val top = ArrayList<FunD>()
        for (i in funs.indices) funs[i] = lift(funs[i], null, top)
        var i = 0
        while (i < classes.size) {
            val c = classes[i]
            val added = ArrayList<FunD>()
            val ms = c.methods.map { lift(it, c, added) }
            if (added.isNotEmpty() || ms.zip(c.methods).any { (a, b) -> a !== b })
                classes[i] = ClassD(c.name, c.fields, c.supers, ms + added, c.line, c.ctorParams, c.inits, c.superArgs, c.typeParams, c.enumEntries, c.isValue, c.superTypeArgs)
            i++
        }
    }

    private fun keepRecv(n: FunD, o: FunD) = FunD(n.name, n.params, n.ret, n.body, n.exprBody, n.line, n.typeParams, o.recv)

    /** Pending `<...>` type arguments of the supertypes of the class being parsed. */
    private val superTA = HashMap<String, List<TypeRef>>()
    private fun superTypeArgs(sn: String) {
        if (!isOp("<")) return
        next()
        val xs = ArrayList<TypeRef>()
        while (!isOp(">")) { xs.add(type()); if (isOp(",")) next() }
        next()
        superTA[sn] = xs
    }
    private fun takeSuperArgs(): Map<String, List<TypeRef>> = HashMap(superTA).also { superTA.clear() }

    /** Private methods declared in each interface (by name). */
    private val privateIfaceFuns = HashMap<String, MutableSet<String>>()

    /**
     * Private interface methods are copied into implementing classes with the defaults that call them; they get a
     * unique name (`$p$I$f`) so a class member with the same name neither clashes with nor captures them.
     */
    private fun renamePrivate(name: String, ms: List<FunD>): List<FunD> {
        val privs = privateIfaceFuns[name]?.filter { n -> ms.any { it.name == n && (it.body != null || it.exprBody != null) } }?.toSet() ?: return ms
        if (privs.isEmpty()) return ms
        fun nn(n: String) = "\$p\$$name\$$n"
        lateinit var sub: Subst
        sub = Subst(emptyMap(), rw = { x ->
            if (x !is CallE) null else {
                val c = x.callee
                val r = when {
                    c is NameE && c.n in privs -> NameE(nn(c.n))
                    c is MemberE && c.n in privs && c.recv is NameE && (c.recv as NameE).n == "this" -> MemberE(c.recv, nn(c.n), false)
                    else -> null
                }
                r?.let { CallE(it.also { e -> e.line = c.line }, x.typeArgs, x.args.map { sub.e(it) }, x.lambda?.let { sub.block(it) }).also { it.line = x.line } }
            }
        })
        return ms.map { m -> val f = sub.fn(m); FunD(if (m.name in privs) nn(m.name) else m.name, f.params, f.ret, f.body, f.exprBody, f.line, f.typeParams, f.recv) }
    }

    /** Declared or inferred return types of the methods of each interface parsed so far. */
    private val ifaceRets = HashMap<String, Map<String, TypeRef>>()

    /**
     * Interface default methods must have a signature before code generation, so the return type of an
     * expression-bodied default without one is inferred syntactically: literals, string templates and `+` with a
     * string, `toString()`, `if`/`when` branches, calls of methods of this interface (or its super-interfaces) with
     * known types. Iterated to a fixed point; methods it cannot type keep the "needs an explicit return type" error.
     */
    private fun inferIfaceRets(name: String, ms: List<FunD>): List<FunD> {
        val known = HashMap<String, TypeRef>()
        for (s in ifaceSupers[name].orEmpty()) ifaceRets[s]?.let { known.putAll(it) }
        ms.forEach { m -> m.ret?.let { known[m.name] = it } }
        fun simple(n: String) = TypeRef(n, emptyList(), false)
        fun guess(e: E): TypeRef? = when (e) {
            is StrL -> simple("String")
            is IntL -> simple(if (e.unsigned) (if (e.long) "ULong" else "UInt") else if (e.long) "Long" else "Int")
            is BoolL -> simple("Boolean")
            is DblL -> simple(if (e.f32) "Float" else "Double")
            is CharL -> simple("Char")
            is IsE -> simple("Boolean")
            is BinE -> when (e.op) {
                "==", "!=", "<", ">", "<=", ">=", "&&", "||" -> simple("Boolean")
                "+" -> guess(e.l)?.takeIf { it.name == "String" } ?: guess(e.l)?.let { l -> guess(e.r)?.takeIf { it.name == l.name && l.name in setOf("Int", "Long", "Double", "Float") } }
                "-", "*", "/", "%" -> guess(e.l)?.let { l -> guess(e.r)?.takeIf { it.name == l.name && l.name in setOf("Int", "Long", "Double", "Float") } }
                else -> null
            }
            is IfE -> {
                fun last(b: Block?) = (b?.stmts?.singleOrNull() as? ExprS)?.e
                val a = last(e.th)?.let { guess(it) }; val b = last(e.el)?.let { guess(it) }
                if (a != null && b != null && a.name == b.name && !a.nullable && !b.nullable && a.args.isEmpty()) a else null
            }
            is CallE -> {
                val c = e.callee
                when {
                    c is MemberE && c.n == "toString" && e.args.isEmpty() -> simple("String")
                    c is NameE -> known[c.n]
                    c is MemberE && c.recv is NameE && (c.recv as NameE).n == "this" -> known[c.n]
                    else -> null
                }
            }
            else -> null
        }
        var out = ms
        while (true) {
            var changed = false
            out = out.map { m ->
                if (m.ret != null || m.exprBody == null || m.typeParams.isNotEmpty()) m
                else guess(m.exprBody)?.let { t -> changed = true; known[m.name] = t; FunD(m.name, m.params, t, m.body, m.exprBody, m.line, m.typeParams, m.recv) } ?: m
            }
            if (!changed) return out
        }
    }

    private fun interfaceDecl(): InterfaceD {
        val line = next().line
        val name = ident()
        val tps = typeParams()
        if (isOp(":")) {
            next()
            val ss = ArrayList<String>()
            while (true) {
                val st = type()
                if (st.args.isNotEmpty()) fail("generic super-interfaces are not supported")
                ss.add(st.name)
                if (isOp(",")) next() else break
            }
            ifaceSupers[name] = ss
        }
        val savedIface = curIface; curIface = name; val savedTps = memberTps; memberTps = tps
        val ms0 = try { if (isOp("{")) members() else emptyList() } finally { curIface = savedIface; memberTps = savedTps }
        val ms = inferIfaceRets(name, renamePrivate(name, ms0))
        val defs = ms.filter { it.body != null || it.exprBody != null }
        if (defs.isNotEmpty() && tps.isNotEmpty()) genericIfaceTps[name] = tps
        if (defs.any { it.ret == null && it.body == null }) fail("default interface methods need an explicit return type")
        ifaceRets[name] = ms.filter { it.ret != null }.associate { it.name to it.ret!! }
        if (defs.isNotEmpty()) ifaceDefaults[name] = defs
        defs.forEach { defaultOwner[it] = name }
        // The interface keeps abstract signatures; implementing classes get copies of the default bodies.
        return InterfaceD(name, ms.map { if (it in defs) FunD(it.name, it.params, it.ret ?: TypeRef("Unit", emptyList(), false), null, null, it.line, it.typeParams) else it }, line, tps)
    }

    /** `<T, reified U : Bound>`: type parameter names; upper bounds are parsed but not checked (monomorphization). */
    private fun typeParams(): List<String> {
        val tps = ArrayList<String>()
        if (!isOp("<")) return tps
        next()
        while (!isOp(">")) {
            while (isId("reified") || isId("out") || isId("in")) next()
            val tl = t[p].line; val tp = ident()
            tps.add(tp)
            if (isOp(":")) { next(); val b = type(); TypeParamBounds.bounds["$tl:$tp"] = b; if (b.name == "Any" && b.nullable) TypeParamBounds.unbounded.add("$tl:$tp") } else TypeParamBounds.unbounded.add("$tl:$tp")
            if (isOp(",")) next()
        }
        next()
        return tps
    }

    /** `context(a: A, _: B)` before the next declaration (a function). */
    private var pendingCtx: List<Param>? = null
    private var ctxN = 0
    private fun isContextDecl() = isId("context") && t[p + 1].k == K.OP && t[p + 1].s == "("
    private fun contextDecl() {
        next(); expectOp("(")
        val ps = ArrayList<Param>()
        while (!isOp(")")) {
            skipNL()
            val n = ident(); expectOp(":"); val ty = type()
            ps.add(Param(if (n == "_") "\$cp\$${ctxN++}" else n, ty, null, false, true))
            skipNL(); if (isOp(",")) next(); skipNL()
        }
        expectOp(")")
        if (pendingCtx != null) fail("several context(...) lists are not supported")
        pendingCtx = ps
        skipNL()
    }
    private fun takeCtx(): List<Param> { val c = pendingCtx ?: emptyList(); pendingCtx = null; return c }
    private fun noCtx() { if (pendingCtx != null) { pendingCtx = null; fail("context parameters are only supported on functions") } }

    private fun function(): FunD {
        val ctxPs = takeCtx()
        val line = next().line
        val typeParams = typeParams()
        // Extension receiver: `fun Recv.name`, `fun Box<T>.name`, `fun String?.name`.
        val save = p
        ident()
        var recv: TypeRef? = null
        val name = if (isOp(".") || isOp("<") || isOp("?") || isOp("?.")) {
            p = save
            val rt = type()
            if (isOp("?.")) { next(); recv = TypeRef(rt.name, rt.args, true); ident() }
            else if (isOp(".")) { next(); recv = rt; ident() }
            else if (rt.args.isEmpty() && rt.name.contains('.')) { recv = TypeRef(rt.name.substringBeforeLast('.'), emptyList(), false); rt.name.substringAfterLast('.') }
            else fail("expected '('")
        } else { p = save; ident() }
        expectOp("(")
        val params = nested(1) {
            val ps = ArrayList<Param>(ctxPs)
            while (!isOp(")")) {
                var va = false
                while (peek().k == K.ID && (peek().s == "vararg" || peek().s == "noinline" || peek().s == "crossinline") && t[p + 1].k == K.ID) { if (peek().s == "vararg") va = true; p++ }
                val n = ident(); expectOp(":"); val ty0 = type()
                // `vararg xs: T` is a list parameter; calls pack their arguments (see the backend's normalize).
                val ty = if (va) TypeRef("List", listOf(ty0), false) else ty0
                ps.add(Param(n, ty, if (isOp("=")) { next(); skipNLOnly(); defaultArg() } else null, va))
                if (isOp(",")) next()
            }
            ps
        }
        expectOp(")")
        val ret = if (isOp(":")) { next(); type() } else null
        return if (isOp("=")) {
            next(); skipNLOnly()
            val outerDecls = pendingDecls; pendingDecls = ArrayList(); bodyLevel++
            val (e, ds) = try { expr() to pendingDecls } finally { pendingDecls = outerDecls; bodyLevel-- }
            if (ds.isEmpty()) FunD(name, params, ret, null, e, line, typeParams, recv)
            else {
                // `= object : T { }`: the anonymous class is declared in a block body; the function returns T.
                val anon = (ds.last() as LocalDeclS).d as ClassD
                val r = ret ?: if (e is CallE && (e.callee as? NameE)?.n == anon.name) TypeRef(anon.supers.firstOrNull() ?: anon.name, anon.superTypeArgs[anon.supers.firstOrNull()] ?: emptyList(), false) else fail("expression body with an anonymous object needs an explicit return type")
                FunD(name, params, r, Block(ds + RetS(e).also { it.line = line }), null, line, typeParams, recv)
            }
        }
        else if (isOp("{")) {
            val outer = localDelegs; localDelegs = HashSet()
            val b = try { block().let { if (localDelegs.isEmpty()) it else delegLocals(it, localDelegs) } } finally { localDelegs = outer }
            FunD(name, params, ret, b, null, line, typeParams, recv)
        }
        else FunD(name, params, ret, null, null, line, typeParams, recv) // abstract (interface) member
    }

    /** Delegated local variables (`val x by d`) of the function body being parsed. */
    private var localDelegs = HashSet<String>()
    /** Reads of a delegated local `x` become `$dg$x.getValue(null, null)`, assignments `$dg$x.setValue(null, null, v)`. */
    private fun delegLocals(b: Block, names: Set<String>): Block {
        fun call(n: String, m: String, line: Int, extra: List<E>) = CallE(MemberE(NameE("\$dg\$$n").also { it.line = line }, m, false).also { it.line = line }, emptyList(), listOf<E>(NullL(), NullL()) + extra, null).also { it.line = line }
        var sb: Subst? = null
        sb = Subst(emptyMap(), rw = { x -> if (x is NameE && x.n in names) (if (x.n in lazyLocals) MemberE(NameE("\$dg\$${x.n}").also { it.line = x.line }, "value", false).also { it.line = x.line }
            else call(x.n, "getValue", x.line, emptyList())) else null },
            srw = { s -> if (s is AssignS && (s.target as? NameE)?.n in names) {
                if (s.op != "=") fail("compound assignment to a delegated local is not supported")
                ExprS(call((s.target as NameE).n, "setValue", s.line, listOf(sb!!.e(s.value)))).also { it.line = s.line } } else null })
        return sb.block(b)
    }

    /** A secondary constructor: parameters, delegation (`this`/`super`/none) with its arguments, optional body. */
    private class SecCtor(val params: List<Param>, val kind: String?, val args: List<E>, val body: Block?, val line: Int)
    private val secCtors = HashMap<String, ArrayList<SecCtor>>()
    /** Factory functions `C$sec$k` for secondary constructors (call sites pick them by argument count and types). */
    private val ctorFactories = ArrayList<FunD>()

    private fun secondaryCtor() {
        val line = next().line
        expectOp("(")
        val ps = nested(1) {
            val xs = ArrayList<Param>()
            while (!isOp(")")) {
                if (isId("vararg")) fail("vararg constructor parameters are not supported")
                val n = ident(); expectOp(":"); val ty = type()
                xs.add(Param(n, ty, if (isOp("=")) { next(); skipNLOnly(); defaultArg() } else null))
                if (isOp(",")) next()
            }
            xs
        }
        expectOp(")")
        var kind: String? = null; var args: List<E> = emptyList()
        if (isOp(":")) {
            next()
            kind = ident()
            if (kind != "this" && kind != "super") fail("expected this(...) or super(...)")
            args = args()
        }
        skipNLOnly()
        val body = if (isOp("{")) block() else null
        secCtors.getOrPut(curClass!!) { ArrayList() }.add(SecCtor(ps, kind, args, body, line))
    }

    /** Turns secondary constructors into a primary (if there is none) plus factory functions. */
    private fun lowerSecondaries(name: String, hasPrimary: Boolean, fields: List<FieldD>, ctorParams: MutableList<Param>, inits: MutableList<Any>,
                                 supers: MutableList<String>, superArgs0: List<E>?, typeParams: List<String>, ms: List<FunD>): List<E>? {
        val secs = secCtors.remove(name) ?: return superArgs0
        if (typeParams.isNotEmpty()) fail("secondary constructors of generic classes are not supported")
        var superArgs = superArgs0
        val rest = ArrayList(secs)
        if (!hasPrimary) {
            val prim = secs.firstOrNull { it.kind != "this" } ?: fail("a class without a primary constructor needs a non-delegating constructor")
            rest.remove(prim)
            ctorParams.addAll(prim.params)
            prim.body?.let { inits.add(it) }
            if (prim.kind == "super") {
                if (supers.isEmpty()) fail("super(...) without a superclass")
                superArgs = prim.args
            }
        }
        for ((k, sc) in rest.withIndex()) {
            if (sc.kind != "this") fail("secondary constructors must delegate to this(...)")
            val self = NameE("\$self")
            val members = (fields.map { it.name } + ms.map { it.name }).toSet() - sc.params.map { it.name }.toSet()
            val sub = Subst(emptyMap(), rw = { x ->
                when {
                    x is NameE && x.n == "this" -> NameE("\$self").also { it.line = x.line }
                    x is NameE && x.n in members -> MemberE(NameE("\$self").also { it.line = x.line }, x.n, false).also { it.line = x.line }
                    else -> null
                }
            }, srw = { x -> if (x is RetS && x.e == null) RetS(NameE("\$self").also { it.line = x.line }).also { it.line = x.line } else null })
            val stmts = ArrayList<S>()
            stmts.add(VarS("\$self", false, null, CallE(NameE(name).also { it.line = sc.line }, emptyList(), sc.args, null).also { it.line = sc.line }).also { it.line = sc.line })
            sc.body?.let { stmts.addAll(sub.block(it).stmts) }
            stmts.add(RetS(self.also { it.line = sc.line }).also { it.line = sc.line })
            ctorFactories.add(FunD("$name\$sec\$$k", sc.params, TypeRef(name, emptyList(), false), Block(stmts), null, sc.line))
        }
        return superArgs
    }

    /** A declaration body `{` on the line after the header: skips the newlines. Always true. */
    private fun braceOnNextLine(): Boolean {
        var i = p
        while (t[i].k == K.NL) i++
        if (i > p && t[i].k == K.OP && t[i].s == "{") p = i
        return true
    }

    private fun classDecl(isEnum: Boolean = false): ClassD {
        val line = next().line
        val name = ident()
        val typeParams = typeParams()
        // `class C private constructor(...)`: the visibility of the primary constructor is not checked.
        if (peek().k == K.ID && peek().s in setOf("private", "public", "internal", "protected") && t[p + 1].k == K.ID && t[p + 1].s == "constructor") p++
        if (isId("constructor") && t[p + 1].k == K.OP && t[p + 1].s == "(") p++
        val fields = ArrayList<FieldD>(); val ctorParams = ArrayList<Param>(); val inits = ArrayList<Any>()
        if (isEnum) enumClasses.add(name)
        if (isEnum) for ((n, ty) in listOf("name" to "String", "ordinal" to "Int")) {
            // Hidden constructor parameters `$name` / `$ordinal` (user parameters may be called `name`).
            val tr = TypeRef(ty, emptyList(), false); ctorParams.add(Param("$$n", tr))
            val f = FieldD(n, tr, false, NameE("$$n")); fields.add(f); inits.add(f)
        }
        val hasPrimary = isOp("(")
        if (isOp("(")) {
            next()
            nested(1) {
                while (!isOp(")")) {
                    while (peek().k == K.ID && peek().s in MEMBER_MODS && t[p + 1].k == K.ID) p++
                    val kw = if (isId("val") || isId("var")) next().s else null
                    val n = ident(); expectOp(":"); val ty = type()
                    ctorParams.add(Param(n, ty, if (isOp("=")) { next(); skipNLOnly(); defaultArg() } else null))
                    if (kw != null) fields.add(FieldD(n, ty, kw == "var"))
                    if (isOp(",")) next()
                }
            }
            expectOp(")")
        }
        val supers = ArrayList<String>(); var superArgs: List<E>? = null
        if (isOp(":")) {
            next()
            while (true) {
                val sn = ident()
                superTypeArgs(sn)
                if (isId("by")) {
                    // Class delegation `I by expr`: a hidden field holding the delegate; forwarders are added later.
                    next()
                    noBraceCall = true
                    val de = try { expr() } finally { noBraceCall = false }
                    val fn = "\$by\$$sn"
                    val f = FieldD(fn, TypeRef(sn, superTA[sn].orEmpty(), false), false, de)
                    fields.add(f); inits.add(f)
                    byDelegs.getOrPut(name) { ArrayList() }.add(sn)
                    supers.add(sn)
                } else if (isOp("(")) {
                    if (superArgs != null) fail("only one superclass is allowed")
                    next()
                    superArgs = nested(1) { val xs = ArrayList<E>(); while (!isOp(")")) { xs.add(expr()); if (isOp(",")) next() }; xs }
                    expectOp(")")
                    supers.add(0, sn)
                } else supers.add(sn)
                if (isOp(",")) next() else break
            }
        }
        if (isEnum && superArgs != null) fail("enum classes cannot have a superclass")
        headerSupers[name] = supers.toList()
        val savedCls = curClass; curClass = name
        try {
            val c = classBody(name, isEnum, fields, ctorParams, inits, supers, superArgs, typeParams, line)
            if (!secCtors.containsKey(name)) return c
            val cps = ArrayList(c.ctorParams); val ins = ArrayList(c.inits); val sups = ArrayList(c.supers)
            val sa = lowerSecondaries(name, hasPrimary, c.fields, cps, ins, sups, c.superArgs, c.typeParams, c.methods)
            return ClassD(c.name, c.fields, sups, c.methods, c.line, cps, ins, sa, c.typeParams, c.enumEntries, c.isValue, c.superTypeArgs)
        } finally { curClass = savedCls }
    }

    private fun classBody(name: String, isEnum: Boolean, fields: ArrayList<FieldD>, ctorParams: List<Param>, inits: ArrayList<Any>,
                          supers: List<String>, superArgs: List<E>?, typeParams: List<String>, line: Int): ClassD {
        val sta = takeSuperArgs()
        var entries: List<Pair<String, List<E>>>? = null
        val ms = if (isEnum) {
            val es = ArrayList<Pair<String, List<E>>>()
            expectOp("{")
            skipNL0()
            while (peek().k == K.ID) {
                val en = ident()
                val args = if (isOp("(")) args() else emptyList()
                if (isOp("{")) {
                    // Entry with a body: a hidden subclass `E__A` of the enum class that forwards the constructor arguments.
                    val sub = "${name}__$en"
                    val saved = curClass; curClass = sub
                    // The entry body is a class body: it may declare properties and init blocks.
                    val efs = ArrayList<FieldD>(); val eins = ArrayList<Any>()
                    val body = try { members(efs, eins) } finally { curClass = saved }
                    // Entries are created eagerly before top-level properties; Kotlin initializes them lazily.
                    if (eins.any { it is Block }) fail("init blocks in enum entry bodies are not supported")
                    val cps = ctorParams.map { Param(it.name, it.type) }
                    hoisted.add(ClassD(sub, efs, listOf(name), body, line, cps, eins, cps.map { NameE(it.name) }))
                    enumBodies["$name$$en"] = sub
                }
                es.add(en to args)
                skipNL0()
                if (isOp(",")) { next(); skipNL0() } else break
            }
            entries = es
            if (isOp(";")) { next(); members(fields, inits, open = false) } else { skipNL0(); expectOp("}"); emptyList() }
        } else if (braceOnNextLine() && isOp("{")) members(fields, inits) else emptyList()
        // An entry body overriding toString needs a virtual base `toString(): String = name`.
        val subs = entries.orEmpty().mapNotNull { enumBodies["$name$${it.first}"] }.toSet()
        val ms2 = if (ms.none { it.name == "toString" } && hoisted.any { it.name in subs && it.methods.any { m -> m.name == "toString" && m.params.isEmpty() } })
            ms + FunD("toString", emptyList(), TypeRef("String", emptyList(), false), null, NameE("name").also { it.line = line }, line) else ms
        return ClassD(name, fields, supers, ms2, line, ctorParams, inits, superArgs, typeParams, entries, superTypeArgs = sta)
    }

    private fun type(): TypeRef {
        if (isOp("(")) {
            // Function type `(A, B) -> R` (TypeRef "$Fn" with args = params + return), or a parenthesized type.
            next()
            val ps = nested(1) { val xs = ArrayList<TypeRef>(); while (!isOp(")")) { if (peek().k == K.ID && t[p + 1].k == K.OP && t[p + 1].s == ":") { p += 2 }; xs.add(type()); if (isOp(",")) next() }; xs }
            expectOp(")")
            val fn = if (isOp("->")) { next(); TypeRef("\$Fn", ps + type(), false) } else ps.singleOrNull() ?: fail("expected '->'")
            return if (isOp("?")) { next(); TypeRef(fn.name, fn.args, true) } else fn
        }
        var name = ident()
        while (isOp(".") && t[p + 1].k == K.ID) { next(); name += "." + ident() }
        val args = ArrayList<TypeRef>()
        if (isOp("<")) {
            next()
            while (!isOp(">")) {
                // Variance (`in T`, `out T`) is ignored; a star projection is `*`.
                if ((isId("in") || isId("out")) && t[p + 1].k == K.ID) next()
                if (isOp("*")) { next(); args.add(TypeRef("*", emptyList(), false)) } else args.add(type())
                if (isOp(",")) next()
            }
            next()
        }
        val nullable = if (isOp("?")) { next(); true } else false
        val base = aliases[name]?.takeIf { args.isEmpty() }?.let { a -> TypeRef(a.name, a.args, a.nullable || nullable) } ?: TypeRef(name, args, nullable)
        // Function type with receiver `R.(A) -> X`: TypeRef "$FnR" with args = receiver + params + return.
        if (isOp(".") && t[p + 1].k == K.OP && t[p + 1].s == "(") {
            next(); next()
            val ps = nested(1) { val xs = ArrayList<TypeRef>(); while (!isOp(")")) { if (peek().k == K.ID && t[p + 1].k == K.OP && t[p + 1].s == ":") { p += 2 }; xs.add(type()); if (isOp(",")) next() }; xs }
            expectOp(")"); expectOp("->")
            return TypeRef("\$FnR", listOf(base) + ps + type(), false)
        }
        return base
    }

    /** Non-generic `typealias N = T`, collected before parsing so aliases may be used before their declaration. */
    private val aliases = HashMap<String, TypeRef>()

    private fun collectAliases() {
        // CharSequence: only String implements it here (StringBuilder is not a CharSequence value), so it is String.
        aliases["CharSequence"] = TypeRef("String", emptyList(), false)
        val save = p
        var depth = 0
        while (peek().k != K.EOF) {
            val tk = peek()
            if (tk.k == K.OP && (tk.s == "{" || tk.s == "(")) depth++
            if (tk.k == K.OP && (tk.s == "}" || tk.s == ")")) depth--
            if (depth == 0 && tk.k == K.ID && tk.s == "typealias" && t[p + 1].k == K.ID && t[p + 2].k == K.OP && t[p + 2].s == "=") {
                p += 3
                val name = t[p - 2].s
                aliases[name] = type()
                continue
            }
            p++
        }
        p = save
    }

    private fun block(lambda: Boolean = false): Block {
        expectOp("{")
        val params = ArrayList<String>(); val ptypes = ArrayList<TypeRef?>()
        val outerDecls = pendingDecls; pendingDecls = ArrayList(); bodyLevel++
        val stmts = try { nested(0) {
            if (lambda) {
                // `x, y: T ->` parameter list (backtracks if it is not one).
                val save = p
                val ps = ArrayList<String>(); val ts = ArrayList<TypeRef?>()
                try {
                    skipNL0()
                    while (peek().k == K.ID) {
                        ps.add(ident())
                        ts.add(if (isOp(":")) { next(); type() } else null)
                        if (isOp(",")) next() else break
                    }
                    if (ps.isNotEmpty() && isOp("->")) { next(); params.addAll(ps); ptypes.addAll(ts) } else p = save
                } catch (_: CompileError) { p = save }
            }
            val ss = ArrayList<S>()
            while (true) {
                skipNL()
                if (isOp("}")) break
                val st = stmt(); ss.addAll(pendingDecls); pendingDecls.clear()
                ss.add(st); ss.addAll(pendingStmts); pendingStmts.clear()
                val tk = t[p]
                if (!(tk.k == K.NL || (tk.k == K.OP && (tk.s == ";" || tk.s == "}")))) fail("expected end of statement")
            }
            ss
        } } finally { pendingDecls = outerDecls; bodyLevel-- }
        expectOp("}")
        return Block(stmts, params, ptypes)
    }

    private fun controlBody(): Block { skipNLOnly(); return if (isOp("{")) block() else Block(listOf(stmt())) }

    private fun destructVar(d: String, n: String, i: Int, isVar: Boolean, line: Int): S =
        VarS(n, isVar, null, CallE(MemberE(NameE(d).also { it.line = line }, "component${i + 1}", false).also { it.line = line }, emptyList(), emptyList(), null).also { it.line = line }).also { it.line = line }

    /** Modifiers that may precede a local declaration; they have no effect here. */
    private val LOCAL_MODIFIERS = setOf("inline", "tailrec", "operator", "infix", "private", "data", "open", "abstract", "sealed", "enum", "value")

    /** A local `fun` / `class` / `object` / `interface` starts at the current token (after optional modifiers). */
    private fun localDeclAhead(): Boolean {
        var i = p
        while (t[i].k == K.ID && t[i].s in LOCAL_MODIFIERS && t[i + 1].k == K.ID) i++
        val k = t[i]; val n = t[i + 1]
        if (k.k != K.ID) return false
        return when (k.s) {
            "fun" -> n.k == K.ID || (n.k == K.OP && n.s == "<")
            "class", "interface" -> n.k == K.ID
            "object" -> n.k == K.ID
            else -> false
        }
    }

    /** Parses a local declaration into a [LocalDeclS]; [liftLocals] hoists it after parsing. */
    private fun localDecl(): S {
        var isData = false; var isEnum = false; var isValue = false
        while (peek().s in LOCAL_MODIFIERS && t[p + 1].k == K.ID) {
            when (next().s) { "data" -> isData = true; "enum" -> isEnum = true; "value" -> isValue = true }
        }
        val saved = curClass
        val d: Any = try {
            when {
                isId("fun") -> function()
                isId("interface") -> interfaceDecl()
                isId("object") -> { next(); objectDecl(ident()) }
                else -> {
                    val c = classDecl(isEnum)
                    if (isValue) fail("local value classes are not supported")
                    if (isData) dataClass(c) else c
                }
            }
        } finally { curClass = saved }
        sawLocal = true
        return LocalDeclS(d)
    }

    private fun stmt(): S {
        val line = peek().line
        if (isContextDecl()) { contextDecl(); if (!localDeclAhead()) noCtx() }
        val s: S = when {
            localDeclAhead() -> localDecl()
            // Loop label `name@ for/while/do`.
            peek().k == K.ID && t[p + 1].k == K.OP && t[p + 1].s == "@" && t[p + 2].k == K.ID && t[p + 2].s in setOf("for", "while", "do") -> {
                val l = next().s; next()
                val x = stmt()
                when (x) { is WhileS -> x.label = l; is ForS -> x.label = l; else -> fail("label on a non-loop") }
                return x
            }
            isId("val") || isId("var") -> {
                val isVar = next().s == "var"
                if (isOp("(")) {
                    next()
                    val names = ArrayList<String>()
                    while (!isOp(")")) { names.add(ident()); if (isOp(":")) { next(); type() }; if (isOp(",")) next() }
                    next()
                    expectOp("="); skipNLOnly()
                    val d = "\$d${destructN++}"
                    names.forEachIndexed { i, n -> if (n != "_") pendingStmts.add(destructVar(d, n, i, isVar, line)) }
                    VarS(d, false, null, expr())
                } else {
                val name = ident()
                val ty = if (isOp(":")) { next(); type() } else null
                if (isId("by")) {
                    next(); skipNLOnly()
                    localDelegs.add(name)
                    val d = dropLazyMode(expr()); if (isLazyCall(d)) lazyLocals.add(name) else lazyLocals.remove(name)
                    return VarS("\$dg\$$name", false, null, d).also { it.line = line }
                }
                expectOp("="); skipNLOnly()
                VarS(name, isVar, ty, expr())
                }
            }
            isId("while") -> {
                next(); expectOp("(")
                val c = nested(1) { expr() }
                expectOp(")")
                WhileS(c, controlBody())
            }
            isId("do") -> {
                next()
                val body = controlBody()
                while (t[p].k == K.NL || (t[p].k == K.OP && t[p].s == ";")) p++
                if (!isId("while")) fail("expected 'while'")
                next(); expectOp("(")
                val c = nested(1) { expr() }
                expectOp(")")
                WhileS(c, body, isDo = true)
            }
            isId("for") -> {
                next(); expectOp("(")
                val names = ArrayList<String>()
                val (n, it) = nested(1) {
                    val n = if (isOp("(") || isOp("[")) {
                        val close = if (isOp("(")) ")" else "]"
                        next(); while (!isOp(close)) { names.add(ident()); if (isOp(":")) { next(); type() }; if (isOp(",")) next() }; next()
                        "\$d${destructN++}"
                    } else ident()
                    if (ident() != "in") fail("expected 'in'"); n to expr()
                }
                expectOp(")")
                val body = controlBody()
                val pre = names.mapIndexedNotNull { i, x -> if (x == "_") null else destructVar(n, x, i, false, line) }
                ForS(n, it, if (pre.isEmpty()) body else Block(pre + body.stmts, body.params, body.paramTypes))
            }
            isId("break") || isId("continue") -> JumpS(next().s == "break").also { j ->
                if (t[p].k == K.OP && t[p].s == "@" && t[p + 1].k == K.ID) { next(); j.label = next().s }
            }
            isId("return") -> {
                next()
                val tk = t[p]
                if (tk.k == K.NL || tk.k == K.EOF || (tk.k == K.OP && (tk.s == "}" || tk.s == ";"))) RetS(null) else RetS(expr())
            }
            else -> {
                val e = expr()
                val tk = t[p]
                when {
                    tk.k == K.OP && tk.s in ASSIGN_OPS -> { p++; skipNLOnly(); AssignS(e, tk.s, expr()) }
                    tk.k == K.OP && (tk.s == "++" || tk.s == "--") -> { p++; IncS(e, tk.s) }
                    else -> ExprS(e)
                }
            }
        }
        s.line = line
        return s
    }

    fun expr(): E = binary(0)

    // Precedence levels, lowest first.
    private val levels: List<Set<String>> = listOf(
        setOf("||"), setOf("&&"), setOf("==", "!="), setOf("<", ">", "<=", ">="), IN_OPS,
        setOf("?:"), INFIX_FUNS, setOf(".."), setOf("+", "-"), setOf("*", "/", "%"),
    )

    private fun binary(level: Int): E {
        if (level == levels.size) {
            var e = prefix()
            while (isId("as")) {
                val ln = next().line
                val safe = if (isOp("?")) { next(); true } else false
                e = AsE(e, type(), safe).also { it.line = ln }
            }
            return e
        }
        var l = binary(level + 1)
        while (true) {
            val tk = peek()
            if (levels[level] === IN_OPS) {
                // `x in r` / `x !in r` (the lexer splits `!in` into `!` and `in`).
                val negIs = tk.k == K.OP && tk.s == "!" && t[p + 1].k == K.ID && t[p + 1].s == "is"
                if (negIs || (tk.k == K.ID && tk.s == "is")) {
                    p += if (negIs) 2 else 1
                    l = IsE(l, type(), negIs).also { it.line = tk.line }
                    continue
                }
                val neg = tk.k == K.OP && tk.s == "!" && t[p + 1].k == K.ID && t[p + 1].s == "in"
                if (!neg && !(tk.k == K.ID && tk.s == "in")) return l
                p += if (neg) 2 else 1; skipNLOnly()
                l = BinE(if (neg) "!in" else "in", l, binary(level + 1)).also { it.line = tk.line }
                continue
            }
            if (levels[level] === INFIX_FUNS && tk.k == K.ID && tk.s in userInfix && tk.s !in INFIX_FUNS) {
                p++; skipNLOnly()
                l = CallE(MemberE(l, tk.s, false).also { it.line = tk.line }, emptyList(), listOf(binary(level + 1)), null).also { it.line = tk.line; it.infix = true }
                continue
            }
            val ok = if (levels[level] === INFIX_FUNS) tk.k == K.ID && tk.s in INFIX_FUNS else tk.k == K.OP && tk.s in levels[level]
            if (!ok) return l
            p++; skipNLOnly()
            l = BinE(tk.s, l, binary(level + 1)).also { it.line = tk.line }
        }
    }

    private fun prefix(): E {
        // Prefix `++x` / `--x`: a PostIncE whose op is "pre++"/"pre--" (yields the new value).
        if (t[p].k == K.OP && (t[p].s == "++" || t[p].s == "--")) {
            val tk = next()
            val e = prefix()
            if (e !is NameE && e !is MemberE && e !is IndexE) fail("${tk.s} needs an assignable operand", tk)
            return PostIncE(e, "pre" + tk.s).also { it.line = tk.line }
        }
        val tk = peek()
        if (tk.k == K.OP && (tk.s == "-" || tk.s == "!" || tk.s == "+")) {
            p++
            val e = prefix()
            return if (tk.s == "+" && (e is IntL || e is DblL)) e else UnE(tk.s, e).also { it.line = tk.line }
        }
        return postfix()
    }

    private fun args(): List<E> {
        expectOp("(")
        val a = nested(1) {
            val xs = ArrayList<E>()
            while (!isOp(")")) {
                if (peek().k == K.ID && t[p + 1].k == K.OP && t[p + 1].s == "=") { val n = ident(); next(); xs.add(NamedE(n, if (isOp("*")) { val l = next().line; UnE("*", expr()).also { it.line = l } } else expr())) }
                else if (isOp("*")) { val l = next().line; xs.add(UnE("*", expr()).also { it.line = l }) } else xs.add(expr())
                if (isOp(",")) next()
            }
            xs
        }
        expectOp(")")
        return a
    }

    /** `"literal".trimIndent()` / `.trimMargin()` on a constant string: folded at compile time. */
    private fun foldStr(e: E, a: List<E>, lam: Block?): E? {
        if (e !is MemberE || e.recv !is StrL || lam != null || !(e.recv as StrL).parts.all { it is String }) return null
        val text = (e.recv as StrL).parts.joinToString("") { it as String }
        val r = when {
            e.n == "trimIndent" && a.isEmpty() -> text.trimIndent()
            e.n == "trimMargin" && a.isEmpty() -> text.trimMargin()
            e.n == "trimMargin" && a.size == 1 && a[0] is StrL && (a[0] as StrL).parts.all { it is String } -> {
                val prefix = (a[0] as StrL).parts.joinToString("") { it as String }
                // Kotlin throws at run time for a blank prefix; keep that behaviour instead of crashing the compiler.
                if (prefix.isBlank()) return CallE(NameE("\$throw").also { it.line = e.line }, emptyList(),
                    listOf(CallE(NameE("IllegalArgumentException").also { it.line = e.line }, emptyList(),
                        listOf(StrL(listOf("marginPrefix must be non-blank string."))), null)), null)
                text.trimMargin(prefix)
            }
            else -> return null
        }
        return StrL(listOf(r))
    }

    /** Arguments kept in place: literals, lambdas and spreads `*xs` (a spread is not a value of its own). */
    private fun constArg(a: E) = a is LambdaE || (a is UnE && a.op == "*") || a is IntL || a is BoolL || a is CharL || a is DblL || a is NullL ||
        (a is StrL && a.parts.all { it is String })

    /**
     * Kotlin evaluates call arguments in source order, but the backend places named arguments in parameter order.
     * A call with named arguments and side-effecting arguments is rewritten to
     * `when { else -> { val $a0 = x; val $a1 = y; f(b = $a0, a = $a1) } }` so evaluation order is preserved.
     */
    private fun inSourceOrder(callee: E, a: List<E>, lam: Block?): E {
        val plain = CallE(callee, emptyList(), a, lam)
        if (a.none { it is NamedE }) return plain
        if (!(callee is NameE || callee is MemberE)) return plain
        val inner = a.map { if (it is NamedE) it.e else it }
        val complex = { x: E -> !constArg(x) && x !is NameE }
        if (inner.none(complex)) return plain
        val line = t[p - 1].line
        val pre = ArrayList<S>()
        fun hoist(v: E): E {
            val tmp = "\$a${argTmp++}"
            pre.add(VarS(tmp, false, null, v).also { it.line = line })
            return NameE(tmp).also { it.line = line }
        }
        // The receiver is evaluated before the arguments (a plain name cannot have side effects).
        var callee2 = callee
        if (callee is MemberE && complex(callee.recv) && !callee.safe) callee2 = MemberE(hoist(callee.recv), callee.n, false).also { it.line = callee.line }
        val args = a.mapIndexed { i, x ->
            val v = inner[i]
            // A name only needs a temporary when a later argument may change it; keeping it keeps smart casts.
            if (constArg(v) || (v is NameE && inner.drop(i + 1).none(complex))) x else {
                val n = hoist(v)
                if (x is NamedE) NamedE(x.name, n).also { it.line = x.line } else n
            }
        }
        val call = CallE(callee2, emptyList(), args, lam).also { it.line = line }
        return WhenE(null, listOf(null to Block(pre + ExprS(call).also { it.line = line }))).also { it.line = line }
    }

    private fun postfix(): E {
        var e = primary()
        while (true) {
            val tk = peek()
            if (tk.k != K.OP) break
            val line = tk.line
            e = when (tk.s) {
                ".", "?." -> { p++; skipNLOnly(); MemberE(e, ident(), tk.s == "?.") }
                "(" -> {
                    val a = args()
                    val lam = if (!noBraceCall && t[p].k == K.OP && t[p].s == "{") block(lambda = true) else null
                    foldStr(e, a, lam) ?: inSourceOrder(e, a, lam)
                }
                "[" -> { p++; val i = nested(1) { expr() }; expectOp("]"); IndexE(e, i) }
                "!!" -> { p++; UnE("!!", e).also { it.line = tk.line } }
                // Callable reference `recv::name`: UnE("::name", recv).
                "::" -> { p++; if (isId("class")) fail("class literals are not supported"); UnE("::" + ident(), e) }
                "++", "--" -> if (e is NameE || e is MemberE || e is IndexE) { p++; PostIncE(e, tk.s) } else break
                "{" -> if (!noBraceCall && (e is MemberE || (e is NameE && e.n !in NO_TRAILING))) CallE(e, emptyList(), emptyList(), block(lambda = true)) else break
                else -> break
            }
            e.line = line
        }
        return e
    }

    private fun primary(): E {
        if (isOp("::")) {
            // `::name`: a reference without receiver (NullL stands for "none").
            val l = next().line
            if (isId("class")) fail("class literals are not supported")
            return UnE("::" + ident(), NullL()).also { it.line = l }
        }
        val tk = next()
        val e: E = when (tk.k) {
            K.INT -> IntL(tk.num, tk.isLong, tk.unsigned)
            K.CHAR -> CharL(tk.num)
            K.DBL -> DblL(tk.dbl, tk.flt)
            K.STR -> StrL(tk.parts.map {
                if (it is TemplateCode) Parser(Lexer(it.code, it.line).lex()).also { p -> p.depth[0] = 1 }.expr() else it
            })
            K.ID -> when (tk.s) {
                "object" -> if (isOp(":") || isOp("{")) anonObject(tk.line) else fail("unexpected 'object'", tk)
                "true" -> BoolL(true)
                "false" -> BoolL(false)
                "null" -> NullL()
                "if" -> ifExpr()
                "when" -> whenExpr()
                "try" -> tryExpr()
                "throw" -> CallE(NameE("\$throw").also { it.line = tk.line }, emptyList(), listOf(expr()), null)
                // `return` / `break` / `continue` used as an expression (`x ?: return 0`, `a + continue`): a block
                // expression whose only statement jumps (type Nothing).
                "return", "break", "continue" -> {
                    p--
                    val j = stmt()
                    WhenE(null, listOf(null to Block(listOf(j)))).also { it.line = tk.line }
                }
                "this" -> if (t[p].k == K.OP && t[p].s == "@" && t[p + 1].k == K.ID) { p++; NameE("this@" + ident()) } else NameE("this")
                else -> {
                    if (tk.s in GENERIC_CALLS && t[p].k == K.OP && t[p].s == "<") {
                        p++
                        val ta = ArrayList<TypeRef>()
                        while (!isOp(">")) { ta.add(type()); if (isOp(",")) next() }
                        next()
                        CallE(NameE(tk.s).also { it.line = tk.line }, ta, args(), null)
                    } else explicitTypeArgs()?.let { ta ->
                        // `f<T>(args) { lambda }` or `f<T> { lambda }`
                        val a = if (isOp("(")) args() else emptyList()
                        val lam = if (!noBraceCall && t[p].k == K.OP && t[p].s == "{") block(lambda = true) else null
                        CallE(NameE(tk.s).also { it.line = tk.line }, ta, a, lam)
                    } ?: NameE(tk.s)
                }
            }
            K.OP -> if (tk.s == "{") { p--; LambdaE(block(lambda = true)) } else if (tk.s == "(") { val x = nested(1) { expr() }; expectOp(")"); x } else fail("unexpected operator", tk)
            else -> fail("unexpected token", tk)
        }
        if (e.line == 0) e.line = tk.line
        return e
    }

    /** `f<Int, String>(...)`: speculatively parses explicit type arguments before a call; backtracks otherwise. */
    private fun explicitTypeArgs(): List<TypeRef>? {
        if (!(t[p].k == K.OP && t[p].s == "<" && t[p + 1].k == K.ID)) return null
        val save = p
        try {
            p++
            val ta = ArrayList<TypeRef>()
            while (!isOp(">")) { ta.add(type()); if (isOp(",")) next() else if (!isOp(">")) { p = save; return null } }
            next()
            if (t[p].k == K.OP && (t[p].s == "(" || t[p].s == "{")) return ta
        } catch (_: CompileError) {}
        p = save
        return null
    }

    private fun tryExpr(): E {
        val body = block()
        val catches = ArrayList<Catch>()
        var fin: Block? = null
        while (true) {
            val save = p
            while (t[p].k == K.NL) p++
            if (isId("catch")) {
                next(); expectOp("(")
                val n = ident(); expectOp(":"); val ty = type()
                expectOp(")")
                catches.add(Catch(n, ty, block()))
            } else if (isId("finally")) { next(); fin = block(); break }
            else { p = save; break }
        }
        if (catches.isEmpty() && fin == null) fail("try needs a catch or finally block")
        return TryE(body, catches, fin)
    }

    private fun ifExpr(): E {
        expectOp("(")
        val c = nested(1) { expr() }
        expectOp(")")
        val th = controlBody()
        val save = p
        while (t[p].k == K.NL || (t[p].k == K.OP && t[p].s == ";")) p++
        if (t[p].k == K.ID && t[p].s == "else") {
            p++
            skipNLOnly()
            val el = if (isId("if")) { val l = next().line; Block(listOf(ExprS(ifExpr().also { it.line = l }))) } else controlBody()
            return IfE(c, th, el)
        }
        p = save
        return IfE(c, th, null)
    }

    /** A `when` branch condition; with a subject, `is T`, `!is T`, `in r` and `!in r` test the subject (`$subj`). */
    private fun whenCond(hasSubj: Boolean): E {
        if (hasSubj) {
            val tk = peek()
            val neg = tk.k == K.OP && tk.s == "!" && t[p + 1].k == K.ID && (t[p + 1].s == "is" || t[p + 1].s == "in")
            val kw = if (neg) t[p + 1].s else if (tk.k == K.ID && (tk.s == "is" || tk.s == "in")) tk.s else null
            if (kw != null) {
                p += if (neg) 2 else 1
                val s = NameE("\$subj").also { it.line = tk.line }
                return (if (kw == "is") IsE(s, type(), neg) else BinE(if (neg) "!in" else "in", s, expr())).also { it.line = tk.line }
            }
        }
        return expr()
    }

    private fun whenExpr(): E {
        val subj = if (isOp("(")) { next(); val s = nested(1) { expr() }; expectOp(")"); s } else null
        expectOp("{")
        val branches = nested(0) {
            val bs = ArrayList<Pair<List<E>?, Block>>()
            while (true) {
                skipNL()
                if (isOp("}")) break
                val conds: List<E>? = if (isId("else")) { next(); null } else {
                    val cs = arrayListOf(whenCond(subj != null))
                    while (isOp(",")) { next(); skipNLOnly(); cs.add(whenCond(subj != null)) }
                    cs
                }
                expectOp("->")
                bs.add(conds to controlBody())
            }
            bs
        }
        expectOp("}")
        return WhenE(subj, branches)
    }
}
