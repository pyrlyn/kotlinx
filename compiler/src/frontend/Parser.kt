package newkotlin

private val ASSIGN_OPS = setOf("=", "+=", "-=", "*=", "/=", "%=")
private val IN_OPS = setOf("in", "!in")
private val INFIX_FUNS = setOf("and", "or", "xor", "shl", "shr", "ushr", "until", "downTo", "step")
private val GENERIC_CALLS = setOf("ArrayList", "mutableListOf", "arrayListOf", "listOf", "HashMap", "hashMapOf", "mutableMapOf")
private val LAMBDA_METHODS = setOf("usePinned", "map", "filter", "forEach", "sumOf", "count")

/** Names after which `{` does not start a trailing lambda. */
private val NO_TRAILING = setOf("try", "do", "else", "finally", "init", "object", "catch", "return", "this")

private val MEMBER_MODS = setOf("override", "open", "public", "private", "protected", "internal", "final", "abstract", "const", "operator", "infix", "sealed", "lateinit")

class Parser(private val t: List<Tok>) {
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

    /** Data class: generated toString, componentN, hashCode and `$equals` (structural `==`) over the constructor properties. */
    private fun dataClass(c: ClassD): ClassD {
        val props = c.ctorParams.filter { p -> c.fields.any { it.name == p.name } }
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
            val one = BinE("==", nm(p.name), MemberE(nm("other"), p.name, false).also { it.line = l }).also { it.line = l }
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

    /** Extension property `val <T> Recv.name: Type get() = ...` (getter only): an extension function `$prop$name`. */
    private fun extProperty(): FunD {
        next()
        val tps = typeParams()
        val rt = type()
        val (recv, name) = if (isOp(".")) { next(); rt to ident() }
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
        while (true) {
            skipNL()
            val tk = peek()
            when {
                tk.k == K.EOF -> break
                tk.k == K.OP && tk.s == "@" -> skipLine()
                tk.k == K.ID && (tk.s == "import" || tk.s == "package") -> skipLine()
                tk.k == K.ID && (tk.s == "value" || tk.s == "inline") && t[p + 1].k == K.ID && t[p + 1].s == "class" -> {
                    p++
                    val c = classDecl()
                    if (c.fields.size != 1 || c.ctorParams.size != 1) fail("a value class needs exactly one property", tk)
                    classes.add(ClassD(c.name, c.fields, c.supers, c.methods, c.line, c.ctorParams, c.inits, c.superArgs, c.typeParams, null, true, c.superTypeArgs))
                }
                tk.k == K.ID && tk.s == "data" && t[p + 1].k == K.ID && t[p + 1].s == "class" -> { p++; classes.add(dataClass(classDecl())) }
                tk.k == K.ID && tk.s in setOf("private", "internal", "public", "inline", "data", "operator", "infix", "tailrec", "const", "open", "final", "abstract", "sealed") -> p++
                tk.k == K.ID && (tk.s == "val" || tk.s == "var") && (t[p + 1].k == K.OP && t[p + 1].s == "<" ||
                    t[p + 1].k == K.ID && t[p + 2].k == K.OP && (t[p + 2].s == "." || t[p + 2].s == "<")) -> funs.add(extProperty())
                tk.k == K.ID && (tk.s == "val" || tk.s == "var") -> { globals.add(stmt() as VarS); if (pendingStmts.isNotEmpty()) fail("top-level destructuring declarations are not supported", tk) }
                tk.k == K.ID && tk.s == "fun" -> funs.add(function())
                tk.k == K.ID && tk.s == "class" -> classes.add(classDecl())
                tk.k == K.ID && tk.s == "enum" && t[p + 1].k == K.ID && t[p + 1].s == "class" -> { p++; classes.add(classDecl(true)) }
                tk.k == K.ID && tk.s == "interface" -> ifaces.add(interfaceDecl())
                tk.k == K.ID && tk.s == "object" -> { next(); classes.add(objectDecl(ident())) }
                else -> fail("unexpected top-level token")
            }
        }
        classes.addAll(companions)
        classes.addAll(hoisted); ifaces.addAll(hoistedIfaces)
        resolveNested(funs, classes, ifaces, globals)
        resolveExtensions(funs, classes)
        val objGlobals = objects.map { n -> VarS(n, false, TypeRef(n, emptyList(), false), CallE(NameE(n), emptyList(), emptyList(), null)) }
        return Program(funs, classes, ifaces, enumGlobals(classes) + globals + objGlobals, companionOf)
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

    private fun objectDecl(name: String): ClassD {
        val line = t[p - 1].line
        val supers = ArrayList<String>()
        if (isOp(":")) {
            next()
            while (true) { val sn = ident(); supers.add(sn); superTypeArgs(sn); if (isOp("(")) fail("objects with a superclass are not supported"); if (isOp(",")) next() else break }
        }
        val fields = ArrayList<FieldD>(); val inits = ArrayList<Any>()
        val sta = takeSuperArgs()
        val saved = curClass; curClass = null
        val ms = try { if (isOp("{")) members(fields, inits) else emptyList() } finally { curClass = saved }
        objects.add(name)
        return ClassD(name, fields, supers, ms, line, emptyList(), inits, superTypeArgs = sta)
    }

    /** Enum entries become top-level immortal instances `E$A` (initialized first, in order) plus the list `E$values`. */
    private fun enumGlobals(classes: List<ClassD>): List<VarS> {
        val gs = ArrayList<VarS>()
        for (c in classes) {
            val es = c.enumEntries ?: continue
            es.forEachIndexed { i, (n, args) ->
                val call = CallE(NameE(c.name), emptyList(), listOf(StrL(listOf(n)), IntL(i.toLong(), false)) + args, null).also { it.line = c.line }
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
                while (peek().k == K.ID && peek().s in MEMBER_MODS && t[p + 1].k == K.ID) p++
                if (props != null && inits != null && (isId("val") || isId("var"))) {
                    val isVar = next().s == "var"
                    val n = ident()
                    val ty = if (isOp(":")) { next(); type() } else null
                    getter("\$prop\$$n", ty, emptyList(), null)?.let { ms.add(it); continue }
                    if (!isOp("=")) fail("body properties need an initializer")
                    next(); skipNLOnly()
                    val f = FieldD(n, ty, isVar, expr())
                    props.add(f); inits.add(f)
                    continue
                }
                if (inits != null && isId("init")) { next(); skipNLOnly(); inits.add(block()); continue }
                if (isId("companion") && t[p + 1].k == K.ID && t[p + 1].s == "object") {
                    val cls = curClass ?: fail("companion objects are only supported in classes")
                    next(); next()
                    val cn = if (peek().k == K.ID) ident() else "Companion"
                    val od = objectDecl("$cls$$cn")
                    companions.add(od)
                    companionOf[cls] = od.name to (od.fields.map { it.name } + od.methods.map { it.name }).toSet() + cn
                    continue
                }
                if (nestedDecl()) continue
                if (!isId("fun")) fail("only member functions are supported in class/interface bodies")
                val f = function()
                if (f.recv != null) fail("member extension functions are not supported")
                ms.add(f)
            }
        }
        expectOp("}")
        return ms
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
                    x is CallE && x.callee is MemberE && x.callee.n in innerOf && !(x.callee.recv is NameE && x.callee.recv.n in byName) ->
                        CallE(NameE(x.callee.n).also { it.line = x.line }, x.typeArgs, listOf(sub.e(x.callee.recv)) + x.args.map { sub.e(it) }, x.lambda?.let { sub.block(it) }).also { it.line = x.line }
                    x is CallE && x.callee is NameE && x.callee.n in outerNames ->
                        CallE(MemberE(outerRef(x.line), x.callee.n, false).also { it.line = x.line }, x.typeArgs, x.args.map { sub.e(it) }, x.lambda?.let { sub.block(it) }).also { it.line = x.line }
                    else -> null
                }
            })
            return sub
        }
        for (i in ifaces.indices) ifaces[i] = ifaces[i].let { d -> InterfaceD(d.name, d.methods.map { keepRecv(rewriter(null, it).fn(it), it) }, d.line, d.typeParams) }
        for (i in globals.indices) globals[i] = rewriter(null, null).s(globals[i]) as VarS
        for (i in funs.indices) funs[i] = keepRecv(rewriter(null, funs[i]).fn(funs[i]), funs[i])
        for (i in classes.indices) {
            val c = classes[i]
            val ms = c.methods.map { keepRecv(rewriter(c.name, it).fn(it), it) }
            val sub = rewriter(c.name, null)
            val fields = c.fields.map { FieldD(it.name, it.type?.let { t -> sub.ty(t) }, it.isVar, it.init?.let { e -> sub.e(e) }) }
            val inits = c.inits.map { if (it is FieldD) fields.first { f -> f.name == it.name } else sub.block(it as Block) }
            val o = innerOf[c.name]
            val of = if (o != null) listOf(FieldD("outer\$", TypeRef(o, emptyList(), false), false)) else emptyList()
            val op = if (o != null) listOf(Param("outer\$", TypeRef(o, emptyList(), false))) else emptyList()
            classes[i] = ClassD(c.name, of + fields, c.supers.map { qualified[it]?.name ?: it }, ms, c.line, op + c.ctorParams.map { Param(it.name, sub.ty(it.type)) }, inits, c.superArgs?.map { sub.e(it) }, c.typeParams,
                c.enumEntries?.map { (n, a) -> n to a.map { sub.e(it) } }, c.isValue, c.superTypeArgs)
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

    private fun interfaceDecl(): InterfaceD {
        val line = next().line
        val name = ident()
        val tps = typeParams()
        val ms = if (isOp("{")) members() else emptyList()
        if (ms.any { it.body != null || it.exprBody != null }) fail("default interface methods are not supported")
        return InterfaceD(name, ms, line, tps)
    }

    /** `<T, reified U : Bound>`: type parameter names; upper bounds are parsed but not checked (monomorphization). */
    private fun typeParams(): List<String> {
        val tps = ArrayList<String>()
        if (!isOp("<")) return tps
        next()
        while (!isOp(">")) {
            while (isId("reified") || isId("out") || isId("in")) next()
            tps.add(ident())
            if (isOp(":")) { next(); type() }
            if (isOp(",")) next()
        }
        next()
        return tps
    }

    private fun function(): FunD {
        val line = next().line
        val typeParams = typeParams()
        // Extension receiver: `fun Recv.name`, `fun Box<T>.name`, `fun String?.name`.
        val save = p
        ident()
        var recv: TypeRef? = null
        val name = if (isOp(".") || isOp("<") || isOp("?")) {
            p = save
            val rt = type()
            if (isOp(".")) { next(); recv = rt; ident() }
            else if (rt.args.isEmpty() && rt.name.contains('.')) { recv = TypeRef(rt.name.substringBeforeLast('.'), emptyList(), false); rt.name.substringAfterLast('.') }
            else fail("expected '('")
        } else { p = save; ident() }
        expectOp("(")
        val params = nested(1) {
            val ps = ArrayList<Param>()
            while (!isOp(")")) {
                val n = ident(); expectOp(":"); ps.add(Param(n, type()))
                if (isOp(",")) next()
            }
            ps
        }
        expectOp(")")
        val ret = if (isOp(":")) { next(); type() } else null
        return if (isOp("=")) { next(); skipNLOnly(); FunD(name, params, ret, null, expr(), line, typeParams, recv) }
        else if (isOp("{")) FunD(name, params, ret, block(), null, line, typeParams, recv)
        else FunD(name, params, ret, null, null, line, typeParams, recv) // abstract (interface) member
    }

    private fun classDecl(isEnum: Boolean = false): ClassD {
        val line = next().line
        val name = ident()
        val typeParams = typeParams()
        val fields = ArrayList<FieldD>(); val ctorParams = ArrayList<Param>(); val inits = ArrayList<Any>()
        if (isEnum) for ((n, ty) in listOf("name" to "String", "ordinal" to "Int")) {
            // Hidden constructor parameters `$name` / `$ordinal` (user parameters may be called `name`).
            val tr = TypeRef(ty, emptyList(), false); ctorParams.add(Param("$$n", tr))
            val f = FieldD(n, tr, false, NameE("$$n")); fields.add(f); inits.add(f)
        }
        if (isOp("(")) {
            next()
            nested(1) {
                while (!isOp(")")) {
                    while (peek().k == K.ID && peek().s in MEMBER_MODS && t[p + 1].k == K.ID) p++
                    val kw = if (isId("val") || isId("var")) next().s else null
                    val n = ident(); expectOp(":"); val ty = type()
                    if (isOp("=")) fail("default arguments are not supported")
                    ctorParams.add(Param(n, ty))
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
        if (isEnum && superArgs != null) fail("enum classes cannot have a superclass")
        val savedCls = curClass; curClass = name
        try { return classBody(name, isEnum, fields, ctorParams, inits, supers, superArgs, typeParams, line) } finally { curClass = savedCls }
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
                if (isOp("{")) fail("enum entries with bodies are not supported")
                es.add(en to args)
                skipNL0()
                if (isOp(",")) { next(); skipNL0() } else break
            }
            entries = es
            if (isOp(";")) { next(); members(fields, inits, open = false) } else { skipNL0(); expectOp("}"); emptyList() }
        } else if (isOp("{")) members(fields, inits) else emptyList()
        return ClassD(name, fields, supers, ms, line, ctorParams, inits, superArgs, typeParams, entries, superTypeArgs = sta)
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
        while (isOp(".")) { next(); name += "." + ident() }
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
        return TypeRef(name, args, nullable)
    }

    private fun block(lambda: Boolean = false): Block {
        expectOp("{")
        val params = ArrayList<String>(); val ptypes = ArrayList<TypeRef?>()
        val stmts = nested(0) {
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
                ss.add(stmt()); ss.addAll(pendingStmts); pendingStmts.clear()
                val tk = t[p]
                if (!(tk.k == K.NL || (tk.k == K.OP && (tk.s == ";" || tk.s == "}")))) fail("expected end of statement")
            }
            ss
        }
        expectOp("}")
        return Block(stmts, params, ptypes)
    }

    private fun controlBody(): Block { skipNLOnly(); return if (isOp("{")) block() else Block(listOf(stmt())) }

    private fun destructVar(d: String, n: String, i: Int, isVar: Boolean, line: Int): S =
        VarS(n, isVar, null, CallE(MemberE(NameE(d).also { it.line = line }, "component${i + 1}", false).also { it.line = line }, emptyList(), emptyList(), null).also { it.line = line }).also { it.line = line }

    private fun stmt(): S {
        val line = peek().line
        val s: S = when {
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
                    val n = if (isOp("(")) {
                        next(); while (!isOp(")")) { names.add(ident()); if (isOp(":")) { next(); type() }; if (isOp(",")) next() }; next()
                        "\$d${destructN++}"
                    } else ident()
                    if (ident() != "in") fail("expected 'in'"); n to expr()
                }
                expectOp(")")
                val body = controlBody()
                val pre = names.mapIndexedNotNull { i, x -> if (x == "_") null else destructVar(n, x, i, false, line) }
                ForS(n, it, if (pre.isEmpty()) body else Block(pre + body.stmts, body.params, body.paramTypes))
            }
            isId("break") || isId("continue") -> JumpS(next().s == "break")
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
        if (level == levels.size) return prefix()
        var l = binary(level + 1)
        while (true) {
            val tk = peek()
            if (levels[level] === IN_OPS) {
                // `x in r` / `x !in r` (the lexer splits `!in` into `!` and `in`).
                val neg = tk.k == K.OP && tk.s == "!" && t[p + 1].k == K.ID && t[p + 1].s == "in"
                if (!neg && !(tk.k == K.ID && tk.s == "in")) return l
                p += if (neg) 2 else 1; skipNLOnly()
                l = BinE(if (neg) "!in" else "in", l, binary(level + 1)).also { it.line = tk.line }
                continue
            }
            if (levels[level] === INFIX_FUNS && tk.k == K.ID && tk.s in userInfix && tk.s !in INFIX_FUNS) {
                p++; skipNLOnly()
                l = CallE(MemberE(l, tk.s, false).also { it.line = tk.line }, emptyList(), listOf(binary(level + 1)), null).also { it.line = tk.line }
                continue
            }
            val ok = if (levels[level] === INFIX_FUNS) tk.k == K.ID && tk.s in INFIX_FUNS else tk.k == K.OP && tk.s in levels[level]
            if (!ok) return l
            p++; skipNLOnly()
            l = BinE(tk.s, l, binary(level + 1)).also { it.line = tk.line }
        }
    }

    private fun prefix(): E {
        val tk = peek()
        if (tk.k == K.OP && (tk.s == "-" || tk.s == "!" || tk.s == "+")) {
            p++
            val e = prefix()
            return if (tk.s == "+") e else UnE(tk.s, e).also { it.line = tk.line }
        }
        return postfix()
    }

    private fun args(): List<E> {
        expectOp("(")
        val a = nested(1) {
            val xs = ArrayList<E>()
            while (!isOp(")")) {
                if (peek().k == K.ID && t[p + 1].k == K.OP && t[p + 1].s == "=") { val n = ident(); next(); xs.add(NamedE(n, expr())) } else xs.add(expr())
                if (isOp(",")) next()
            }
            xs
        }
        expectOp(")")
        return a
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
                    val lam = if (t[p].k == K.OP && t[p].s == "{") block(lambda = true) else null
                    CallE(e, emptyList(), a, lam)
                }
                "[" -> { p++; val i = nested(1) { expr() }; expectOp("]"); IndexE(e, i) }
                "!!" -> { p++; e }
                "++", "--" -> if (e is NameE || e is MemberE || e is IndexE) { p++; PostIncE(e, tk.s) } else break
                "{" -> if (e is MemberE || (e is NameE && e.n !in NO_TRAILING)) CallE(e, emptyList(), emptyList(), block(lambda = true)) else break
                else -> break
            }
            e.line = line
        }
        return e
    }

    private fun primary(): E {
        val tk = next()
        val e: E = when (tk.k) {
            K.INT -> IntL(tk.num, tk.isLong, tk.unsigned)
            K.CHAR -> CharL(tk.num)
            K.DBL -> DblL(tk.dbl)
            K.STR -> StrL(tk.parts.map {
                if (it is TemplateCode) Parser(Lexer(it.code, it.line).lex()).also { p -> p.depth[0] = 1 }.expr() else it
            })
            K.ID -> when (tk.s) {
                "true" -> BoolL(true)
                "false" -> BoolL(false)
                "null" -> NullL()
                "if" -> ifExpr()
                "when" -> whenExpr()
                "try" -> tryExpr()
                "throw" -> CallE(NameE("\$throw").also { it.line = tk.line }, emptyList(), listOf(expr()), null)
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
                        val lam = if (t[p].k == K.OP && t[p].s == "{") block(lambda = true) else null
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

    private fun whenExpr(): E {
        val subj = if (isOp("(")) { next(); val s = nested(1) { expr() }; expectOp(")"); s } else null
        expectOp("{")
        val branches = nested(0) {
            val bs = ArrayList<Pair<List<E>?, Block>>()
            while (true) {
                skipNL()
                if (isOp("}")) break
                val conds: List<E>? = if (isId("else")) { next(); null } else {
                    val cs = arrayListOf(expr())
                    while (isOp(",")) { next(); skipNLOnly(); cs.add(expr()) }
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
