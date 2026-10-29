package newkotlin

private val ASSIGN_OPS = setOf("=", "+=", "-=", "*=", "/=", "%=")
private val INFIX_FUNS = setOf("and", "or", "xor", "shl", "shr", "ushr", "until", "downTo", "step")
private val GENERIC_CALLS = setOf("ArrayList", "mutableListOf", "arrayListOf", "listOf", "HashMap", "hashMapOf", "mutableMapOf")
private val LAMBDA_METHODS = setOf("usePinned", "map", "filter", "forEach", "sumOf", "count")

private val MEMBER_MODS = setOf("override", "open", "public", "private", "protected", "internal", "final", "abstract", "const")

class Parser(private val t: List<Tok>) {
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

    fun program(): Program {
        val globals = ArrayList<VarS>(); val funs = ArrayList<FunD>(); val classes = ArrayList<ClassD>(); val ifaces = ArrayList<InterfaceD>()
        while (true) {
            skipNL()
            val tk = peek()
            when {
                tk.k == K.EOF -> break
                tk.k == K.OP && tk.s == "@" -> skipLine()
                tk.k == K.ID && (tk.s == "import" || tk.s == "package") -> skipLine()
                tk.k == K.ID && tk.s in setOf("private", "internal", "public", "inline", "data", "const", "open", "final", "abstract", "sealed") -> p++
                tk.k == K.ID && (tk.s == "val" || tk.s == "var") -> globals.add(stmt() as VarS)
                tk.k == K.ID && tk.s == "fun" -> funs.add(function())
                tk.k == K.ID && tk.s == "class" -> classes.add(classDecl())
                tk.k == K.ID && tk.s == "enum" && t[p + 1].k == K.ID && t[p + 1].s == "class" -> { p++; classes.add(classDecl(true)) }
                tk.k == K.ID && tk.s == "interface" -> ifaces.add(interfaceDecl())
                tk.k == K.ID && tk.s == "object" -> { next(); classes.add(objectDecl(ident())) }
                else -> fail("unexpected top-level token")
            }
        }
        classes.addAll(companions)
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
            while (true) { supers.add(ident()); if (isOp("(")) fail("objects with a superclass are not supported"); if (isOp(",")) next() else break }
        }
        val fields = ArrayList<FieldD>(); val inits = ArrayList<Any>()
        val saved = curClass; curClass = null
        val ms = try { if (isOp("{")) members(fields, inits) else emptyList() } finally { curClass = saved }
        objects.add(name)
        return ClassD(name, fields, supers, ms, line, emptyList(), inits)
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
                if (!isId("fun")) fail("only member functions are supported in class/interface bodies")
                ms.add(function())
            }
        }
        expectOp("}")
        return ms
    }

    private fun interfaceDecl(): InterfaceD {
        val line = next().line
        val name = ident()
        val ms = if (isOp("{")) members() else emptyList()
        if (ms.any { it.body != null || it.exprBody != null }) fail("default interface methods are not supported")
        return InterfaceD(name, ms, line)
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
        val name = ident()
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
        return if (isOp("=")) { next(); skipNLOnly(); FunD(name, params, ret, null, expr(), line, typeParams) }
        else if (isOp("{")) FunD(name, params, ret, block(), null, line, typeParams)
        else FunD(name, params, ret, null, null, line, typeParams) // abstract (interface) member
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
        return ClassD(name, fields, supers, ms, line, ctorParams, inits, superArgs, typeParams, entries)
    }

    private fun type(): TypeRef {
        var name = ident()
        while (isOp(".")) { next(); name += "." + ident() }
        val args = ArrayList<TypeRef>()
        if (isOp("<")) {
            next()
            while (!isOp(">")) { args.add(type()); if (isOp(",")) next() }
            next()
        }
        val nullable = if (isOp("?")) { next(); true } else false
        return TypeRef(name, args, nullable)
    }

    private fun block(lambda: Boolean = false): Block {
        expectOp("{")
        val params = ArrayList<String>()
        val stmts = nested(0) {
            if (lambda) {
                var q = p
                while (t[q].k == K.NL) q++
                val ps = ArrayList<String>()
                while (t[q].k == K.ID) {
                    ps.add(t[q].s); q++
                    if (t[q].k == K.OP && t[q].s == ",") q++ else break
                }
                if (ps.isNotEmpty() && t[q].k == K.OP && t[q].s == "->") { params.addAll(ps); p = q + 1 }
            }
            val ss = ArrayList<S>()
            while (true) {
                skipNL()
                if (isOp("}")) break
                ss.add(stmt())
                val tk = t[p]
                if (!(tk.k == K.NL || (tk.k == K.OP && (tk.s == ";" || tk.s == "}")))) fail("expected end of statement")
            }
            ss
        }
        expectOp("}")
        return Block(stmts, params)
    }

    private fun controlBody(): Block { skipNLOnly(); return if (isOp("{")) block() else Block(listOf(stmt())) }

    private fun stmt(): S {
        val line = peek().line
        val s: S = when {
            isId("val") || isId("var") -> {
                val isVar = next().s == "var"
                val name = ident()
                val ty = if (isOp(":")) { next(); type() } else null
                expectOp("="); skipNLOnly()
                VarS(name, isVar, ty, expr())
            }
            isId("while") -> {
                next(); expectOp("(")
                val c = nested(1) { expr() }
                expectOp(")")
                WhileS(c, controlBody())
            }
            isId("for") -> {
                next(); expectOp("(")
                val (n, it) = nested(1) { val n = ident(); if (ident() != "in") fail("expected 'in'"); n to expr() }
                expectOp(")")
                ForS(n, it, controlBody())
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
        setOf("||"), setOf("&&"), setOf("==", "!="), setOf("<", ">", "<=", ">="),
        setOf("?:"), INFIX_FUNS, setOf(".."), setOf("+", "-"), setOf("*", "/", "%"),
    )

    private fun binary(level: Int): E {
        if (level == levels.size) return prefix()
        var l = binary(level + 1)
        while (true) {
            val tk = peek()
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
            while (!isOp(")")) { xs.add(expr()); if (isOp(",")) next() }
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
                "{" -> if (e is MemberE && e.n in LAMBDA_METHODS) CallE(e, emptyList(), emptyList(), block(lambda = true)) else break
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
                else -> {
                    if (tk.s in GENERIC_CALLS && t[p].k == K.OP && t[p].s == "<") {
                        p++
                        val ta = ArrayList<TypeRef>()
                        while (!isOp(">")) { ta.add(type()); if (isOp(",")) next() }
                        next()
                        CallE(NameE(tk.s).also { it.line = tk.line }, ta, args(), null)
                    } else explicitTypeArgs()?.let { ta -> CallE(NameE(tk.s).also { it.line = tk.line }, ta, args(), null) } ?: NameE(tk.s)
                }
            }
            K.OP -> if (tk.s == "(") { val x = nested(1) { expr() }; expectOp(")"); x } else fail("unexpected operator", tk)
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
            if (t[p].k == K.OP && t[p].s == "(") return ta
        } catch (_: CompileError) {}
        p = save
        return null
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
