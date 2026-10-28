package newkotlin

/** An SSA value (or constant) with its type. `owned` = caller holds a +1 reference (ref types only). */
class Val(val v: String, val t: Ty, val owned: Boolean = false)

private class Local(val slot: String, val t: Ty, val isVar: Boolean)
private class Scope { val vars = HashMap<String, Local>(); val refs = ArrayList<Local>() }
private class FunSig(val name: String, val params: List<Ty>, val ret: Ty)
/** Object layout: { i64 refcount, ptr vtable, fields... }; vtable[0] = release function. */
private class ClassInfo(val name: String, val fields: List<Pair<String, Ty>>, val supers: List<String> = emptyList(), val methods: Map<String, FunD> = emptyMap()) {
    val llType = "%C.$name"
    fun index(f: String) = fields.indexOfFirst { it.first == f }
}
private class IfaceInfo(val name: String, val methods: Map<String, FunSig>)

/** libc functions callable by name from Kotlin/Native-style `platform.posix` code. */
private val POSIX = mapOf(
    "getenv" to (listOf(Ty.Str) to Ty.RawPtr),
    "fopen" to (listOf(Ty.Str, Ty.Str) to Ty.RawPtr),
    "fseek" to (listOf(Ty.RawPtr, Ty.I64, Ty.I32) to Ty.I32),
    "ftell" to (listOf(Ty.RawPtr) to Ty.I64),
    "fread" to (listOf(Ty.RawPtr, Ty.I64, Ty.I64, Ty.RawPtr) to Ty.I64),
    "fclose" to (listOf(Ty.RawPtr) to Ty.I32),
)
private val CONSTS = mapOf("SEEK_SET" to 0L, "SEEK_CUR" to 1L, "SEEK_END" to 2L)
private val NANOS = setOf("kotlin.system.getTimeNanos", "getTimeNanos", "System.nanoTime", "kotlin.system.System.nanoTime")

/** Native target: typed LLVM IR generation (linked by clang with runtime/runtime.c). */
class NativeBackend(private val prog: Program) {
    private val globals = StringBuilder()
    private val funcs = StringBuilder()
    private val strMap = HashMap<String, String>()
    private val classes = HashMap<String, ClassInfo>()
    private val sigs = HashMap<String, FunSig>()
    private val ifaces = HashMap<String, IfaceInfo>()
    private val methodSigs = HashMap<String, FunSig>() // "Class.method"
    private val slots = ArrayList<String>()             // interface method name -> vtable slot (index + 1)
    private var owner: ClassInfo? = null

    // per-function state
    private var allocas = StringBuilder()
    private var body = StringBuilder()
    private var tmpN = 0
    private var lblN = 0
    private var cur = "entry"
    private var term = false
    private val scopes = ArrayList<Scope>()
    private var retTy: Ty = Ty.Unit
    private var line = 0

    private fun fail(msg: String): Nothing = throw CompileError("line $line: $msg")
    private fun tmp() = "%t${tmpN++}"
    private fun label(p: String) = "$p${lblN++}"
    private fun emit(s: String) {
        if (term) startBlock(label("dead"))
        body.append("  ").append(s).append('\n')
    }
    private fun emitT(s: String): String { val t = tmp(); emit("$t = $s"); return t }
    private fun startBlock(l: String) {
        if (!term) body.append("  br label %$l\n")
        body.append("$l:\n"); cur = l; term = false
    }
    private fun br(l: String) { emit("br label %$l"); term = true }
    private fun condBr(c: String, a: String, b: String) { emit("br i1 $c, label %$a, label %$b"); term = true }

    private fun cstr(s: String): String = strMap.getOrPut(s) {
        val name = "@.s${strMap.size}"
        val bytes = s.encodeToByteArray()
        val enc = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 32..126 && c != '"'.code && c != '\\'.code) enc.append(c.toChar())
            else enc.append('\\').append(c.toString(16).uppercase().padStart(2, '0'))
        }
        globals.append("$name = private unnamed_addr constant [${bytes.size + 1} x i8] c\"$enc\\00\"\n")
        name
    }

    // ---------------------------------------------------------------- types

    private fun resolve(t: TypeRef): Ty = when (t.name) {
        "Int" -> Ty.I32
        "Long" -> Ty.I64
        "UInt" -> Ty.U32
        "ULong" -> Ty.U64
        "Byte" -> Ty.I8
        "Boolean" -> Ty.I1
        "Double" -> Ty.F64
        "Unit" -> Ty.Unit
        "String" -> Ty.Str
        "Char" -> Ty.Char
        "ByteArray" -> Ty.Arr(Ty.I8)
        "IntArray" -> Ty.Arr(Ty.I32)
        "LongArray" -> Ty.Arr(Ty.I64)
        "DoubleArray" -> Ty.Arr(Ty.F64)
        "StringBuilder" -> Ty.SB
        "HashMap", "MutableMap", "Map" -> mapTy(resolve(t.args[0]), resolve(t.args[1]))
        "ArrayList", "MutableList", "List" -> Ty.ListTy(resolve(t.args.single()))
        "Array" -> if (t.args.singleOrNull()?.name == "String") Ty.Args else fail("unsupported type $t")
        else -> if (t.name in classes) Ty.Cls(t.name) else if (t.name in ifaces) Ty.Iface(t.name) else if (t.name.startsWith("CPointer")) Ty.RawPtr else fail("unknown type $t")
    }

    private fun mapTy(k: Ty, v: Ty): Ty {
        if (!(k.isInt || k == Ty.Char)) fail("HashMap keys must be Int/Long/Char, found $k")
        if (v.llvm == "ptr" || v == Ty.Unit) fail("HashMap values must be primitives, found $v")
        return Ty.MapTy(k, v)
    }

    private fun releaseFn(t: Ty): String? = when (t) {
        is Ty.Cls -> "@rel.${t.name}"
        is Ty.ListTy -> "@rt_list_release"
        is Ty.MapTy -> "@rt_map_release"
        is Ty.Iface -> "@rt_release_dyn"
        Ty.SB -> "@rt_sb_release"
        else -> null
    }

    private fun retain(v: String) = emit("call void @rt_retain(ptr $v)")
    private fun release(v: Val) { releaseFn(v.t)?.let { emit("call void $it(ptr ${v.v})") } }
    private fun releaseIfOwned(v: Val) { if (v.owned && v.t.isRef) release(v) }
    /** Turns a value into a +1 reference that can be stored. */
    private fun consume(v: Val): Val {
        if (!v.t.isRef || v.owned) return v
        retain(v.v); return Val(v.v, v.t, true)
    }

    private fun coerce(v: Val, t: Ty): Val {
        if (v.t == t || v.t == Ty.Nothing) return v
        if (v.t.isInt && t.isInt) {
            return when {
                t.llvm == v.t.llvm -> Val(v.v, t)
                t.rank > v.t.rank -> Val(emitT("${if (v.t.isUnsigned) "zext" else "sext"} ${v.t.llvm} ${v.v} to ${t.llvm}"), t)
                else -> Val(emitT("trunc ${v.t.llvm} ${v.v} to ${t.llvm}"), t)
            }
        }
        if (v.t.isInt && t == Ty.F64) return Val(emitT("${if (v.t.isUnsigned) "uitofp" else "sitofp"} ${v.t.llvm} ${v.v} to double"), t)
        if ((v.t == Ty.RawPtr || v.t == Ty.Str) && (t == Ty.RawPtr || t == Ty.Str)) return Val(v.v, t)
        if (v.v == "null" && t.llvm == "ptr") return Val("null", t)
        if (v.t is Ty.Cls && t is Ty.Iface && t.name in classes.getValue(v.t.name).supers) return Val(v.v, t, v.owned)
        fail("type mismatch: expected $t, found ${v.t}")
    }

    private fun toSlot(v: Val): String = when (v.t.llvm) {
        "ptr" -> emitT("ptrtoint ptr ${v.v} to i64")
        "i64" -> v.v
        "double" -> emitT("bitcast double ${v.v} to i64")
        "i1" -> emitT("zext i1 ${v.v} to i64")
        else -> emitT("sext ${v.t.llvm} ${v.v} to i64")
    }

    private fun fromSlot(s: String, t: Ty): String = when (t.llvm) {
        "ptr" -> emitT("inttoptr i64 $s to ptr")
        "i64" -> s
        "double" -> emitT("bitcast i64 $s to double")
        else -> emitT("trunc i64 $s to ${t.llvm}")
    }

    /** Bounds-checked element pointer of a typed array ({ i64 length; elements }). */
    private fun elemPtr(r: Val, i: Val): String {
        val et = (r.t as Ty.Arr).elem
        val len = emitT("load i64, ptr ${r.v}")
        val ok = label("idx.ok"); val bad = label("idx.bad")
        emit("br i1 ${emitT("icmp ult i64 ${i.v}, $len")}, label %$ok, label %$bad, !prof !0"); term = true
        startBlock(bad)
        emit("call void @rt_oob(i64 ${i.v}, i64 $len)"); emit("unreachable"); term = true
        startBlock(ok)
        return emitT("getelementptr ${et.llvm}, ptr ${emitT("getelementptr i8, ptr ${r.v}, i64 8")}, i64 ${i.v}")
    }

    private fun mapKey(k: Val, m: Ty.MapTy): String = toSlot(coerce(k, m.k))

    // ---------------------------------------------------------------- program

    fun generate(): String {
        for (c in prog.classes) classes[c.name] = ClassInfo(c.name, emptyList())
        for (i in prog.ifaces) ifaces[i.name] = IfaceInfo(i.name, emptyMap())
        for (i in prog.ifaces) {
            line = i.line
            ifaces[i.name] = IfaceInfo(i.name, i.methods.associate { m -> m.name to FunSig(m.name, m.params.map { resolve(it.type) }, m.ret?.let { resolve(it) } ?: Ty.Unit) })
            for (m in i.methods) if (m.name !in slots) slots.add(m.name)
        }
        for (c in prog.classes) {
            line = c.line
            for (sup in c.supers) if (sup !in ifaces) fail("unknown interface $sup (class inheritance is not supported)")
            classes[c.name] = ClassInfo(c.name, c.fields.map { it.name to resolve(it.type) }, c.supers, c.methods.associateBy { it.name })
        }
        for (c in prog.classes) for (m in c.methods) {
            line = m.line
            val ret = m.ret?.let { resolve(it) } ?: if (m.body != null) Ty.Unit else fail("method '${m.name}' needs an explicit return type")
            methodSigs["${c.name}.${m.name}"] = FunSig(m.name, m.params.map { resolve(it.type) }, ret)
        }
        for (c in classes.values) genClass(c)
        for (f in prog.funs) {
            line = f.line
            val ret = f.ret?.let { resolve(it) } ?: if (f.body != null) Ty.Unit else fail("expression-bodied function '${f.name}' needs an explicit return type")
            if (f.name in sigs) fail("overloads are not supported: ${f.name}")
            sigs[f.name] = FunSig(f.name, f.params.map { resolve(it.type) }, ret)
        }
        val main = sigs["main"] ?: throw CompileError("no main function")
        for (f in prog.funs) genFun(f, null)
        for (c in prog.classes) for (m in c.methods) genFun(m, classes.getValue(c.name))
        val sb = StringBuilder()
        sb.append("; generated by newkotlinc\n").append(PRELUDE).append(globals).append('\n').append(funcs)
        sb.append("define i32 @main(i32 %argc, ptr %argv) {\n  store i32 %argc, ptr @rt_argc\n")
        sb.append("  call void @k.main()\n  call void @rt_exit_flush()\n  ret i32 0\n}\n")
        if (main.ret != Ty.Unit) throw CompileError("main must return Unit")
        return sb.toString()
    }

    private fun genClass(c: ClassInfo) {
        globals.append("${c.llType} = type { i64, ptr${c.fields.joinToString("") { ", " + it.second.llvm }} }\n")
        val vt = listOf("ptr @rel.${c.name}") + slots.map { if (it in c.methods) "ptr @m.${c.name}.$it" else "ptr null" }
        globals.append("@vt.${c.name} = internal constant [${vt.size} x ptr] [${vt.joinToString(", ")}]\n")
        val f = StringBuilder()
        f.append("define internal void @rel.${c.name}(ptr %p) {\nentry:\n")
        f.append("  %isnull = icmp eq ptr %p, null\n  br i1 %isnull, label %done, label %dec\n")
        f.append("dec:\n  %c = load i64, ptr %p\n  %c1 = sub i64 %c, 1\n  %z = icmp eq i64 %c1, 0\n  br i1 %z, label %free, label %keep\n")
        f.append("keep:\n  store i64 %c1, ptr %p\n  br label %done\n")
        f.append("free:\n")
        c.fields.forEachIndexed { i, (_, t) ->
            releaseFn(t)?.let { fn ->
                f.append("  %f$i = getelementptr ${c.llType}, ptr %p, i32 0, i32 ${i + 2}\n  %v$i = load ptr, ptr %f$i\n  call void $fn(ptr %v$i)\n")
            }
        }
        f.append("  %szp = getelementptr ${c.llType}, ptr null, i32 1\n  %sz = ptrtoint ptr %szp to i64\n")
        f.append("  call void @rt_free(ptr %p, i64 %sz)\n  br label %done\ndone:\n  ret void\n}\n\n")
        funcs.append(f)
    }

    private fun genFun(f: FunD, cls: ClassInfo?) {
        if (cls != null && f.body == null && f.exprBody == null) fail("abstract method ${f.name} in class ${cls.name}")
        val sig = if (cls != null) methodSigs.getValue("${cls.name}.${f.name}") else sigs.getValue(f.name)
        owner = cls
        allocas = StringBuilder(); body = StringBuilder(); tmpN = 0; lblN = 0; cur = "entry"; term = false
        scopes.clear(); retTy = sig.ret; line = f.line
        scopes.add(Scope())
        val params = ArrayList<String>()
        if (cls != null) params.add("ptr %this")
        f.params.forEachIndexed { i, p ->
            val t = sig.params[i]
            if (t == Ty.Args) { declare(p.name, t, false, false); return@forEachIndexed }
            params.add("${t.llvm} %a.${p.name}")
            val l = declare(p.name, t, false, false)
            emit("store ${t.llvm} %a.${p.name}, ptr ${l.slot}")
        }
        if (f.exprBody != null) {
            val v = genExpr(f.exprBody)
            if (!term) emitReturn(v)
        } else {
            genBlockBody(f.body!!, false)
            if (!term) {
                if (retTy == Ty.Unit) { releaseAll(); emit("ret void"); term = true } else emit("unreachable")
            }
        }
        val name = if (cls != null) "m.${cls.name}.${f.name}" else if (f.name == "main") "k.main" else "k.${f.name}"
        val attrs = if (f.name == "main") "" else " nounwind"
        funcs.append("define internal ${sig.ret.llvm} @$name(${params.joinToString(", ")})$attrs {\nentry:\n")
        funcs.append(allocas).append(body).append("}\n\n")
    }

    private fun declare(name: String, t: Ty, isVar: Boolean, ownsRef: Boolean): Local {
        val slot = "%l.$name.${tmpN++}"
        val lt = if (t == Ty.Args) "ptr" else t.llvm
        allocas.append("  $slot = alloca $lt\n")
        val l = Local(slot, t, isVar)
        scopes.last().vars[name] = l
        if (ownsRef && t.isRef) { allocas.append("  store ptr null, ptr $slot\n"); scopes.last().refs.add(l) }
        return l
    }

    private fun lookup(n: String): Local? { for (i in scopes.indices.reversed()) scopes[i].vars[n]?.let { return it }; return null }

    private fun releaseScope(s: Scope) { for (l in s.refs) release(Val(emitT("load ptr, ptr ${l.slot}"), l.t)) }
    private fun releaseAll() { for (s in scopes.reversed()) releaseScope(s) }

    private fun emitReturn(v0: Val) {
        if (retTy == Ty.Unit) { releaseIfOwned(v0); releaseAll(); emit("ret void"); term = true; return }
        var v = coerce(v0, retTy)
        if (v.t == Ty.Nothing) return
        if (v.t.isRef) v = consume(v)
        releaseAll()
        emit("ret ${retTy.llvm} ${v.v}"); term = true
    }

    // ---------------------------------------------------------------- statements

    /** Generates a block in a new scope. With `want`, the last expression statement is the block's value. */
    private fun genBlockBody(b: Block, want: Boolean): Val {
        scopes.add(Scope())
        var res = Val("", Ty.Unit)
        b.stmts.forEachIndexed { i, s ->
            if (want && i == b.stmts.lastIndex && s is ExprS) {
                line = s.line
                res = genExpr(s.e)
                if (res.t.isRef) res = consume(res)
            } else genStmt(s)
        }
        if (!term) releaseScope(scopes.last())
        scopes.removeAt(scopes.size - 1)
        return res
    }

    private fun genStmt(s: S) {
        line = s.line
        when (s) {
            is VarS -> {
                val v0 = genExpr(s.init)
                if (v0.t == Ty.Nothing) return
                val t = s.type?.let { resolve(it) } ?: v0.t
                if (t == Ty.Unit) fail("cannot store Unit in '${s.name}'")
                var v = coerce(v0, t)
                if (t.isRef) v = consume(v)
                val l = declare(s.name, t, s.isVar, true)
                emit("store ${t.llvm} ${v.v}, ptr ${l.slot}")
            }
            is AssignS -> {
                val value = if (s.op == "=") s.value else BinE(s.op.dropLast(1), s.target, s.value).also { it.line = s.line }
                assign(s.target, value)
            }
            is IncS -> assign(s.target, BinE(if (s.op == "++") "+" else "-", s.target, IntL(1, false)).also { it.line = s.line })
            is WhileS -> {
                val lc = label("while.cond"); val lb = label("while.body"); val le = label("while.end")
                startBlock(lc)
                val c = coerce(genExpr(s.cond), Ty.I1)
                condBr(c.v, lb, le)
                startBlock(lb)
                genBlockBody(s.body, false)
                if (!term) br(lc)
                startBlock(le)
            }
            is ForS -> genFor(s)
            is RetS -> {
                if (s.e == null) { if (retTy != Ty.Unit) fail("missing return value"); releaseAll(); emit("ret void"); term = true }
                else emitReturn(genExpr(s.e))
            }
            is ExprS -> releaseIfOwned(genExpr(s.e))
        }
    }

    private fun assign(target: E, value: E) {
        when (target) {
            is NameE -> {
                val l = lookup(target.n)
                if (l == null && owner != null && owner!!.index(target.n) >= 0) return assign(MemberE(NameE("this"), target.n, false), value)
                if (l == null) fail("unknown variable '${target.n}'")
                if (!l.isVar) fail("val '${target.n}' cannot be reassigned")
                var v = coerce(genExpr(value), l.t)
                if (l.t.isRef) {
                    v = consume(v)
                    val old = emitT("load ptr, ptr ${l.slot}")
                    emit("store ptr ${v.v}, ptr ${l.slot}")
                    release(Val(old, l.t))
                } else emit("store ${l.t.llvm} ${v.v}, ptr ${l.slot}")
            }
            is MemberE -> {
                val r = genExpr(target.recv)
                val ct = r.t as? Ty.Cls ?: fail("cannot assign to ${r.t}.${target.n}")
                val ci = classes.getValue(ct.name)
                val idx = ci.index(target.n).takeIf { it >= 0 } ?: fail("no field ${target.n} in ${ct.name}")
                val ft = ci.fields[idx].second
                var v = coerce(genExpr(value), ft)
                val ptr = emitT("getelementptr ${ci.llType}, ptr ${r.v}, i32 0, i32 ${idx + 2}")
                if (ft.isRef) {
                    v = consume(v)
                    val old = emitT("load ptr, ptr $ptr")
                    emit("store ptr ${v.v}, ptr $ptr")
                    release(Val(old, ft))
                } else emit("store ${ft.llvm} ${v.v}, ptr $ptr")
                releaseIfOwned(r)
            }
            is IndexE -> {
                val r = genExpr(target.recv)
                val i0 = genExpr(target.idx)
                val i = if (r.t is Ty.MapTy) i0 else coerce(i0, Ty.I64)
                when (val rt = r.t) {
                    is Ty.Arr -> {
                        val q = elemPtr(r, i)
                        val v = coerce(genExpr(value), rt.elem)
                        emit("store ${rt.elem.llvm} ${v.v}, ptr $q")
                    }
                    is Ty.MapTy -> {
                        val v = coerce(genExpr(value), rt.v)
                        emit("call void @rt_map_put(ptr ${r.v}, i64 ${mapKey(i0, rt)}, i64 ${toSlot(v)})")
                    }
                    is Ty.ListTy -> {
                        val v = consume(coerce(genExpr(value), rt.elem))
                        val old = emitT("call i64 @rt_list_set(ptr ${r.v}, i64 ${i.v}, i64 ${toSlot(v)})")
                        if (rt.elem.isRef) release(Val(fromSlot(old, rt.elem), rt.elem))
                    }
                    else -> fail("cannot index ${r.t}")
                }
                releaseIfOwned(r)
            }
            else -> fail("invalid assignment target")
        }
    }

    private fun genFor(s: ForS) {
        val it = s.iter
        val lc = label("for.cond"); val lb = label("for.body"); val ls = label("for.step"); val le = label("for.end")
        scopes.add(Scope())
        if (it is BinE && (it.op == ".." || it.op == "until")) {
            val lo0 = genExpr(it.l); val hi0 = genExpr(it.r)
            val t = if (lo0.t == Ty.I64 || hi0.t == Ty.I64) Ty.I64 else Ty.I32
            val lo = coerce(lo0, t); val hi = coerce(hi0, t)
            val idx = declare(s.name, t, false, false)
            emit("store ${t.llvm} ${lo.v}, ptr ${idx.slot}")
            startBlock(lc)
            val i = emitT("load ${t.llvm}, ptr ${idx.slot}")
            val c = emitT("icmp ${if (it.op == "..") "sle" else "slt"} ${t.llvm} $i, ${hi.v}")
            condBr(c, lb, le)
            startBlock(lb)
            genBlockBody(s.body, false)
            startBlock(ls)
            val i2 = emitT("load ${t.llvm}, ptr ${idx.slot}")
            if (it.op == "..") {
                // avoid overflow when hi == MAX
                val done = emitT("icmp eq ${t.llvm} $i2, ${hi.v}")
                val inc = label("for.inc")
                condBr(done, le, inc)
                startBlock(inc)
            }
            emit("store ${t.llvm} ${emitT("add ${t.llvm} $i2, 1")}, ptr ${idx.slot}")
            br(lc)
            startBlock(le)
        } else {
            val coll = genExpr(it)
            val elemT = when (val ct = coll.t) { is Ty.Arr -> ct.elem; Ty.Str -> Ty.Char; is Ty.ListTy -> ct.elem; else -> fail("cannot iterate over ${coll.t}") }
            val arr = coll.t as? Ty.Arr
            val idx = "%l.idx.${tmpN++}"
            allocas.append("  $idx = alloca i64\n")
            emit("store i64 0, ptr $idx")
            val v = declare(s.name, elemT, false, false)
            val data = if (arr != null) emitT("getelementptr i8, ptr ${coll.v}, i64 8") else ""
            val len = if (arr != null) emitT("load i64, ptr ${coll.v}") else if (coll.t == Ty.Str) emitT("call i64 @strlen(ptr ${coll.v})") else ""
            startBlock(lc)
            val i = emitT("load i64, ptr $idx")
            val n = if (len != "") len else emitT("call i64 @rt_list_size(ptr ${coll.v})")
            condBr(emitT("icmp slt i64 $i, $n"), lb, le)
            startBlock(lb)
            val e = if (arr != null) emitT("load ${elemT.llvm}, ptr ${emitT("getelementptr ${elemT.llvm}, ptr $data, i64 $i")}")
            else if (coll.t == Ty.Str) emitT("zext i8 ${emitT("load i8, ptr ${emitT("getelementptr i8, ptr ${coll.v}, i64 $i")}")} to i32")
            else fromSlot(emitT("call i64 @rt_list_get(ptr ${coll.v}, i64 $i)"), elemT)
            emit("store ${elemT.llvm} $e, ptr ${v.slot}")
            genBlockBody(s.body, false)
            startBlock(ls)
            emit("store i64 ${emitT("add i64 ${emitT("load i64, ptr $idx")}, 1")}, ptr $idx")
            br(lc)
            startBlock(le)
            releaseIfOwned(coll)
        }
        scopes.removeAt(scopes.size - 1)
    }

    // ---------------------------------------------------------------- expressions

    private fun flatten(e: E): String? = when (e) {
        is NameE -> if (lookup(e.n) == null) e.n else null
        is MemberE -> flatten(e.recv)?.let { "$it.${e.n}" }
        else -> null
    }

    private fun genExpr(e: E): Val {
        if (e.line != 0) line = e.line
        return when (e) {
            is IntL -> when {
                e.unsigned -> if (e.long || e.v !in 0L..0xFFFF_FFFFL) Val("${e.v}", Ty.U64) else Val("${e.v.toInt()}", Ty.U32)
                e.long || e.v !in Int.MIN_VALUE..Int.MAX_VALUE -> Val("${e.v}", Ty.I64)
                else -> Val("${e.v}", Ty.I32)
            }
            is PostIncE -> {
                val old = genExpr(e.target)
                if (old.t.llvm == "ptr") fail("${e.op} on ${old.t}")
                assign(e.target, BinE(if (e.op == "++") "+" else "-", e.target, IntL(1, false)).also { it.line = e.line })
                old
            }
            is DblL -> Val("0x" + e.v.toRawBits().toULong().toString(16).uppercase().padStart(16, '0'), Ty.F64)
            is BoolL -> Val(if (e.v) "true" else "false", Ty.I1)
            is CharL -> Val("${e.v}", Ty.Char)
            is NullL -> Val("null", Ty.RawPtr)
            is StrL -> genTemplate(e)
            is NameE -> {
                val l = lookup(e.n)
                when {
                    l != null && l.t == Ty.Args -> Val("null", Ty.Args)
                    l != null -> Val(emitT("load ${l.t.llvm}, ptr ${l.slot}"), l.t)
                    e.n == "this" && owner != null -> Val("%this", Ty.Cls(owner!!.name))
                    owner != null && owner!!.index(e.n) >= 0 -> property(Val("%this", Ty.Cls(owner!!.name)), e.n)
                    e.n in CONSTS -> Val("${CONSTS.getValue(e.n)}", Ty.I32)
                    else -> fail("unknown name '${e.n}'")
                }
            }
            is MemberE -> genMember(e)
            is CallE -> genCall(e)
            is IndexE -> {
                val r = genExpr(e.recv)
                val i0 = genExpr(e.idx)
                val i = if (r.t is Ty.MapTy) i0 else coerce(i0, Ty.I64)
                val res = when (val rt = r.t) {
                    is Ty.Arr -> Val(emitT("load ${rt.elem.llvm}, ptr ${elemPtr(r, i)}"), rt.elem)
                    is Ty.MapTy -> Val(fromSlot(emitT("call i64 @rt_map_get(ptr ${r.v}, i64 ${mapKey(i0, rt)})"), rt.v), rt.v)
                    Ty.Str -> Val(emitT("zext i8 ${emitT("load i8, ptr ${emitT("getelementptr i8, ptr ${r.v}, i64 ${i.v}")}")} to i32"), Ty.Char)
                    is Ty.ListTy -> Val(fromSlot(emitT("call i64 @rt_list_get(ptr ${r.v}, i64 ${i.v})"), rt.elem), rt.elem)
                    else -> fail("cannot index ${r.t}")
                }
                if (r.owned) { val o = consume(res); releaseIfOwned(r); o } else res
            }
            is BinE -> genBin(e)
            is UnE -> {
                val v = genExpr(e.e)
                when (e.op) {
                    "!" -> Val(emitT("xor i1 ${coerce(v, Ty.I1).v}, true"), Ty.I1)
                    "-" -> when {
                        v.t == Ty.F64 -> Val(emitT("fneg double ${v.v}"), Ty.F64)
                        v.t.isInt -> { val w = coerce(v, if (v.t == Ty.I8) Ty.I32 else v.t); Val(emitT("sub ${w.t.llvm} 0, ${w.v}"), w.t) }
                        else -> fail("unary minus on ${v.t}")
                    }
                    else -> fail("unknown unary ${e.op}")
                }
            }
            is IfE -> {
                val c = coerce(genExpr(e.cond), Ty.I1)
                val lt = label("if.then"); val lf = label("if.else"); val le = label("if.end")
                condBr(c.v, lt, lf)
                val res = ArrayList<Pair<Val, String>>()
                startBlock(lt)
                val a = genBlockBody(e.th, e.el != null)
                if (!term) { res.add(a to cur); br(le) }
                startBlock(lf)
                val b = if (e.el != null) genBlockBody(e.el, true) else Val("", Ty.Unit)
                if (!term) { res.add(b to cur); br(le) }
                startBlock(le)
                merge(res, e.el != null)
            }
            is WhenE -> genWhen(e)
        }
    }

    /** Joins branch results with a phi when every live branch produced a value of the same type. */
    private fun merge(res: List<Pair<Val, String>>, exhaustive: Boolean): Val {
        if (res.isEmpty()) { emit("unreachable"); term = true; return Val("undef", Ty.Nothing) }
        val t = res[0].first.t
        if (!exhaustive || t == Ty.Unit || res.any { it.first.t != t }) {
            for ((v, _) in res) if (v.t.isRef && v.owned) fail("if/when branches yield references of different types; assign the result or make the types match")
            return Val("", Ty.Unit)
        }
        if (res.size == 1) return res[0].first
        val phi = emitT("phi ${t.llvm} " + res.joinToString(", ") { "[ ${it.first.v}, %${it.second} ]" })
        return Val(phi, t, t.isRef)
    }

    private fun genWhen(e: WhenE): Val {
        val subj = e.subj?.let { genExpr(it) }
        val le = label("when.end")
        val res = ArrayList<Pair<Val, String>>()
        var hasElse = false
        for ((conds, blk) in e.branches) {
            if (conds == null) {
                hasElse = true
                val v = genBlockBody(blk, true)
                if (!term) { res.add(v to cur); br(le) }
                break
            }
            val lt = label("when.then"); val ln = label("when.next")
            var c: String? = null
            for (ce in conds) {
                val x = if (subj != null) compare("==", subj, genExpr(ce)).v else coerce(genExpr(ce), Ty.I1).v
                c = if (c == null) x else emitT("or i1 $c, $x")
            }
            condBr(c!!, lt, ln)
            startBlock(lt)
            val v = genBlockBody(blk, true)
            if (!term) { res.add(v to cur); br(le) }
            startBlock(ln)
        }
        if (!hasElse) { res.add(Val("", Ty.Unit) to cur) }
        if (!term) br(le)
        startBlock(le)
        return merge(res, hasElse)
    }

    private fun genTemplate(e: StrL): Val {
        if (e.parts.all { it is String }) return Val(cstr(e.parts.joinToString("")), Ty.Str)
        val sb = emitT("call ptr @rt_sb_new()")
        for (p in e.parts) {
            if (p is String) { if (p.isNotEmpty()) emit("call void @rt_sb_str(ptr $sb, ptr ${cstr(p)})") }
            else appendVal("rt_sb_", "ptr $sb, ", genExpr(p as E))
        }
        return Val(emitT("call ptr @rt_sb_done(ptr $sb)"), Ty.Str)
    }

    /** Calls `<prefix>{str,i64,f64,bool}(<lead> value)` for printing / string building. */
    private fun appendVal(prefix: String, lead: String, v: Val) {
        when {
            v.t == Ty.Str || v.t == Ty.RawPtr -> emit("call void @${prefix}str($lead ptr ${v.v})")
            v.t == Ty.Char -> emit("call void @${prefix}char($lead i32 ${v.v})")
            v.t.isUnsigned -> emit("call void @${prefix}u64($lead i64 ${coerce(v, Ty.U64).v})")
            v.t.isInt -> emit("call void @${prefix}i64($lead i64 ${coerce(v, Ty.I64).v})")
            v.t == Ty.F64 -> emit("call void @${prefix}f64($lead double ${v.v})")
            v.t == Ty.I1 -> emit("call void @${prefix}bool($lead i1 ${v.v})")
            v.t is Ty.Cls -> emit("call void @${prefix}str($lead ptr ${cstr(v.t.name + "@obj")})")
            else -> fail("cannot convert ${v.t} to string")
        }
        releaseIfOwned(v)
    }

    private fun genMember(e: MemberE): Val {
        flatten(e)?.let { if (it == "Long.MAX_VALUE") return Val("9223372036854775807", Ty.I64); if (it == "Int.MAX_VALUE") return Val("2147483647", Ty.I32) }
        if (e.safe) return safe(e.recv) { r -> property(r, e.n) }
        return property(genExpr(e.recv), e.n)
    }

    private fun property(r: Val, n: String): Val {
        val res = when (val t = r.t) {
            is Ty.Cls -> {
                val ci = classes.getValue(t.name)
                val idx = ci.index(n).takeIf { it >= 0 } ?: fail("no field $n in ${t.name}")
                val ft = ci.fields[idx].second
                Val(emitT("load ${ft.llvm}, ptr ${emitT("getelementptr ${ci.llType}, ptr ${r.v}, i32 0, i32 ${idx + 2}")}"), ft)
            }
            is Ty.ListTy -> if (n == "size") Val(emitT("trunc i64 ${emitT("call i64 @rt_list_size(ptr ${r.v})")} to i32"), Ty.I32) else fail("unknown property $n")
            is Ty.Arr -> if (n == "size") Val(emitT("trunc i64 ${emitT("load i64, ptr ${r.v}")} to i32"), Ty.I32) else fail("unknown property $n")
            is Ty.MapTy -> if (n == "size") Val(emitT("trunc i64 ${emitT("call i64 @rt_map_size(ptr ${r.v})")} to i32"), Ty.I32) else fail("unknown property $n")
            Ty.SB -> if (n == "length") Val(emitT("trunc i64 ${emitT("call i64 @rt_sb_len(ptr ${r.v})")} to i32"), Ty.I32) else fail("unknown property $n")
            Ty.Char -> if (n == "code") Val(r.v, Ty.I32) else fail("unknown property $n")
            Ty.Args -> if (n == "size") Val(emitT("sub i32 ${emitT("load i32, ptr @rt_argc")}, 1"), Ty.I32) else fail("unknown property $n")
            Ty.Str -> if (n == "length") Val(emitT("trunc i64 ${emitT("call i64 @strlen(ptr ${r.v})")} to i32"), Ty.I32) else fail("unknown property $n")
            else -> fail("unknown property ${r.t}.$n")
        }
        if (r.owned) { val o = consume(res); releaseIfOwned(r); return o }
        return res
    }

    /** `recv?.x`: evaluates `f` only when recv is non-null; the result must be a pointer type. */
    private fun safe(recvE: E, f: (Val) -> Val): Val {
        val r = genExpr(recvE)
        if (r.t.llvm != "ptr") return f(r)
        val from = cur
        val lc = label("safe.call"); val le = label("safe.end")
        condBr(emitT("icmp eq ptr ${r.v}, null"), le, lc)
        startBlock(lc)
        val v = f(r)
        if (v.t.llvm != "ptr") fail("safe call result must be a reference")
        val vFrom = cur
        br(le)
        startBlock(le)
        return Val(emitT("phi ptr [ null, %$from ], [ ${v.v}, %$vFrom ]"), v.t)
    }

    private fun genCall(e: CallE): Val {
        val callee = e.callee
        val qn = flatten(callee)
        if (qn != null && qn in NANOS) return Val(emitT("call i64 @rt_nanos()"), Ty.I64)
        if (qn != null && (qn == "kotlin.math.sqrt" || qn == "kotlin.math.abs")) return genNamedCall(qn, e)
        if (callee is NameE && lookup(callee.n) == null) return genNamedCall(callee.n, e)
        if (callee is MemberE) {
            if (callee.safe) return safe(callee.recv) { r -> method(r, callee.n, e) }
            return method(genExpr(callee.recv), callee.n, e)
        }
        fail("unsupported call")
    }

    private fun genNamedCall(n: String, e: CallE): Val {
        owner?.let { o -> if (n in o.methods) return callMethod(Val("%this", Ty.Cls(o.name)), o, n, e) }
        when (n) {
            "sqrt", "kotlin.math.sqrt", "abs", "kotlin.math.abs" -> {
                val a = genExpr(e.args.single())
                if (n.endsWith("abs") && a.t.isInt) {
                    val w = coerce(a, if (a.t == Ty.I64) Ty.I64 else Ty.I32)
                    val neg = emitT("sub ${w.t.llvm} 0, ${w.v}")
                    return Val(emitT("select i1 ${emitT("icmp slt ${w.t.llvm} ${w.v}, 0")}, ${w.t.llvm} $neg, ${w.t.llvm} ${w.v}"), w.t)
                }
                val fn = if (n.endsWith("sqrt")) "llvm.sqrt.f64" else "llvm.fabs.f64"
                return Val(emitT("call double @$fn(double ${coerce(a, Ty.F64).v})"), Ty.F64)
            }
            "StringBuilder" -> {
                val sb = emitT("call ptr @rt_sb_new()")
                e.args.singleOrNull()?.let { a -> val v = genExpr(a); if (v.t == Ty.Str) appendVal("rt_sb_", "ptr $sb, ", v) else releaseIfOwned(v) }
                return Val(sb, Ty.SB, true)
            }
            "HashMap", "hashMapOf", "mutableMapOf" -> {
                if (e.typeArgs.size != 2 || e.args.isNotEmpty()) fail("$n needs explicit key/value types and no arguments, e.g. $n<Int, Long>()")
                val t = mapTy(resolve(e.typeArgs[0]), resolve(e.typeArgs[1]))
                return Val(emitT("call ptr @rt_map_new()"), t, true)
            }
            "IntArray", "LongArray", "DoubleArray" -> {
                val et = when (n) { "IntArray" -> Ty.I32; "LongArray" -> Ty.I64; else -> Ty.F64 }
                val size = coerce(genExpr(e.args.single()), Ty.I64)
                return Val(emitT("call ptr @rt_arr_new(i64 ${size.v}, i64 ${et.size})"), Ty.Arr(et))
            }
            "println", "print" -> {
                val a = e.args.singleOrNull()
                if (a is StrL) for (p in a.parts) {
                    if (p is String) { if (p.isNotEmpty()) emit("call void @rt_print_str(ptr ${cstr(p)})") }
                    else appendVal("rt_print_", "", genExpr(p as E))
                } else if (a != null) appendVal("rt_print_", "", genExpr(a))
                if (n == "println") emit("call void @rt_newline()")
                return Val("", Ty.Unit)
            }
            "error", "TODO" -> {
                val m = if (e.args.isEmpty()) Val(cstr("An operation is not implemented."), Ty.Str) else coerce(genExpr(e.args[0]), Ty.Str)
                emit("call void @rt_panic(ptr ${m.v})"); emit("unreachable"); term = true
                return Val("undef", Ty.Nothing)
            }
            "check", "require" -> {
                val c = coerce(genExpr(e.args[0]), Ty.I1)
                val ok = label("check.ok"); val bad = label("check.fail")
                condBr(c.v, ok, bad)
                startBlock(bad)
                val m = if (e.lambda != null) coerce(genBlockBody(e.lambda, true), Ty.Str) else Val(cstr(if (n == "check") "Check failed." else "Failed requirement."), Ty.Str)
                emit("call void @rt_panic(ptr ${m.v})"); emit("unreachable"); term = true
                startBlock(ok)
                return Val("", Ty.Unit)
            }
            "ArrayList", "mutableListOf", "arrayListOf" -> {
                val et = resolve(e.typeArgs.singleOrNull() ?: fail("$n needs an explicit element type, e.g. $n<Long>()"))
                if (e.args.isNotEmpty()) fail("$n with arguments is not supported")
                val rel = releaseFn(et) ?: "null"
                return Val(emitT("call ptr @rt_list_new(ptr $rel)"), Ty.ListTy(et), true)
            }
            "ByteArray" -> {
                val size = coerce(genExpr(e.args.single()), Ty.I64)
                return Val(emitT("call ptr @rt_arr_new(i64 ${size.v}, i64 1)"), Ty.Arr(Ty.I8))
            }
        }
        POSIX[n]?.let { (ps, ret) ->
            if (ps.size != e.args.size) fail("$n expects ${ps.size} arguments")
            val args = e.args.mapIndexed { i, a -> "${ps[i].llvm} ${coerce(genExpr(a), ps[i]).v}" }
            return Val(emitT("call ${ret.llvm} @$n(${args.joinToString(", ")})"), ret)
        }
        classes[n]?.let { ci ->
            if (e.args.size != ci.fields.size) fail("constructor $n expects ${ci.fields.size} arguments")
            val vals = e.args.mapIndexed { i, a -> consume(coerce(genExpr(a), ci.fields[i].second)) }
            val szp = emitT("getelementptr ${ci.llType}, ptr null, i32 1")
            val p = emitT("call ptr @rt_alloc(i64 ${emitT("ptrtoint ptr $szp to i64")})")
            emit("store i64 1, ptr $p")
            emit("store ptr @vt.$n, ptr ${emitT("getelementptr ${ci.llType}, ptr $p, i32 0, i32 1")}")
            vals.forEachIndexed { i, v -> emit("store ${v.t.llvm} ${v.v}, ptr ${emitT("getelementptr ${ci.llType}, ptr $p, i32 0, i32 ${i + 2}")}") }
            return Val(p, Ty.Cls(n), true)
        }
        val sig = sigs[n] ?: fail("unknown function '$n'")
        if (sig.params.size != e.args.size) fail("$n expects ${sig.params.size} arguments")
        val vals = e.args.mapIndexed { i, a -> coerce(genExpr(a), sig.params[i]) }
        val args = vals.filter { it.t != Ty.Args }.joinToString(", ") { "${it.t.llvm} ${it.v}" }
        val call = "call ${sig.ret.llvm} @k.$n($args)"
        val res = if (sig.ret == Ty.Unit) { emit(call); Val("", Ty.Unit) } else Val(emitT(call), sig.ret, sig.ret.isRef)
        vals.forEach { releaseIfOwned(it) }
        return res
    }

    private fun method(r: Val, n: String, e: CallE): Val {
        val t = r.t
        val res: Val = when {
            t.isNum && n in setOf("toInt", "toUInt", "toLong", "toULong", "toByte", "toDouble", "toString") -> {
                if (n == "toString") { val sb = emitT("call ptr @rt_sb_new()"); appendVal("rt_sb_", "ptr $sb, ", r); return Val(emitT("call ptr @rt_sb_done(ptr $sb)"), Ty.Str) }
                val target = when (n) { "toInt" -> Ty.I32; "toUInt" -> Ty.U32; "toLong" -> Ty.I64; "toULong" -> Ty.U64; "toByte" -> Ty.I8; else -> Ty.F64 }
                when {
                    t == Ty.F64 && target.isInt -> Val(emitT("fptosi double ${r.v} to ${target.llvm}"), target)
                    else -> coerce(r, target)
                }
            }
            (t == Ty.RawPtr || t == Ty.Str) && n == "toKString" -> Val(r.v, Ty.Str)
            t == Ty.Str && n == "toString" -> r
            t == Ty.Pinned && n == "addressOf" -> {
                val i = coerce(genExpr(e.args.single()), Ty.I64)
                Val(emitT("getelementptr i8, ptr ${emitT("getelementptr i8, ptr ${r.v}, i64 8")}, i64 ${i.v}"), Ty.RawPtr)
            }
            (t is Ty.Cls || t is Ty.Iface) && e.lambda == null -> {
                val v = if (t is Ty.Cls) callMethod(r, classes.getValue(t.name), n, e) else callIface(r, ifaces.getValue((t as Ty.Iface).name), n, e)
                releaseIfOwned(r); return v
            }
            t == Ty.Char && (n == "toInt" || n == "code") -> Val(r.v, Ty.I32)
            t.isInt && n == "toChar" -> Val(coerce(r, Ty.I32).v, Ty.Char)
            t == Ty.SB && n == "append" -> {
                val v = genExpr(e.args.single())
                appendVal("rt_sb_", "ptr ${r.v}, ", v)
                Val(r.v, Ty.SB, false)
            }
            t == Ty.SB && n == "toString" -> Val(emitT("call ptr @rt_sb_tostring(ptr ${r.v})"), Ty.Str)
            t is Ty.MapTy && (n == "containsKey" || n == "get" || n == "put" || n == "getOrDefault") -> {
                val k = mapKey(genExpr(e.args[0]), t)
                when (n) {
                    "containsKey" -> Val(emitT("icmp ne i32 ${emitT("call i32 @rt_map_contains(ptr ${r.v}, i64 $k)")}, 0"), Ty.I1)
                    "get" -> Val(fromSlot(emitT("call i64 @rt_map_get(ptr ${r.v}, i64 $k)"), t.v), t.v)
                    "getOrDefault" -> Val(fromSlot(emitT("call i64 @rt_map_get_or(ptr ${r.v}, i64 $k, i64 ${toSlot(coerce(genExpr(e.args[1]), t.v))})"), t.v), t.v)
                    else -> { emit("call void @rt_map_put(ptr ${r.v}, i64 $k, i64 ${toSlot(coerce(genExpr(e.args[1]), t.v))})"); Val("", Ty.Unit) }
                }
            }
            t is Ty.ListTy && e.lambda != null && n in setOf("map", "filter", "forEach", "sumOf", "count") -> listLambda(r, t, n, e.lambda)
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
                val v = consume(coerce(genExpr(e.args.single()), t.elem))
                emit("call void @rt_list_add(ptr ${r.v}, i64 ${toSlot(v)})")
                Val("true", Ty.I1)
            }
            t is Ty.ListTy && n == "get" -> {
                val i = coerce(genExpr(e.args.single()), Ty.I64)
                val v = Val(fromSlot(emitT("call i64 @rt_list_get(ptr ${r.v}, i64 ${i.v})"), t.elem), t.elem)
                if (r.owned) consume(v) else v
            }
            else -> fail("unknown method ${r.t}.$n()")
        }
        releaseIfOwned(r)
        return res
    }

    private fun methodArgs(sig: FunSig, e: CallE): List<Val> {
        if (sig.params.size != e.args.size) fail("${sig.name} expects ${sig.params.size} arguments")
        return e.args.mapIndexed { i, a -> coerce(genExpr(a), sig.params[i]) }
    }

    private fun finishCall(call: String, ret: Ty, args: List<Val>): Val {
        val res = if (ret == Ty.Unit) { emit(call); Val("", Ty.Unit) } else Val(emitT(call), ret, ret.isRef)
        args.forEach { releaseIfOwned(it) }
        return res
    }

    /** Statically bound call of a class's own method. */
    private fun callMethod(r: Val, ci: ClassInfo, n: String, e: CallE): Val {
        val sig = methodSigs["${ci.name}.$n"] ?: fail("unknown method ${ci.name}.$n()")
        val args = methodArgs(sig, e)
        return finishCall("call ${sig.ret.llvm} @m.${ci.name}.$n(ptr ${r.v}${args.joinToString("") { ", ${it.t.llvm} ${it.v}" }})", sig.ret, args)
    }

    /** Interface call through the object's vtable (slot = global interface-method index + 1). */
    private fun callIface(r: Val, ii: IfaceInfo, n: String, e: CallE): Val {
        val sig = ii.methods[n] ?: fail("unknown method ${ii.name}.$n()")
        val args = methodArgs(sig, e)
        val vt = emitT("load ptr, ptr ${emitT("getelementptr i8, ptr ${r.v}, i64 8")}")
        val fp = emitT("load ptr, ptr ${emitT("getelementptr ptr, ptr $vt, i64 ${slots.indexOf(n) + 1}")}")
        return finishCall("call ${sig.ret.llvm} $fp(ptr ${r.v}${args.joinToString("") { ", ${it.t.llvm} ${it.v}" }})", sig.ret, args)
    }

    /** Inline expansion of map/filter/forEach/sumOf/count (Kotlin's are inline functions too). */
    private fun listLambda(r: Val, t: Ty.ListTy, n: String, lam: Block): Val {
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
                if (t.elem.isRef) retain(elem.v)
                emit("call void @rt_list_add(ptr $res, i64 ${toSlot(elem)})")
            }
            "forEach" -> releaseIfOwned(v)
            "sumOf" -> {
                if (!v.t.isNum) fail("sumOf selector must return a number")
                accTy = if (v.t == Ty.I8) Ty.I32 else v.t
                val cur = emitT("load ${accTy.llvm}, ptr $acc")
                val nv = arith("+", Val(cur, accTy), v)
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
                "  store ${accTy.llvm} ${if (accTy == Ty.F64) "0.0" else "0"}, ptr $acc\n"
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

    private fun genBin(e: BinE): Val {
        when (e.op) {
            "&&", "||" -> {
                val a = coerce(genExpr(e.l), Ty.I1)
                val from = cur
                val lr = label("logic.rhs"); val le = label("logic.end")
                if (e.op == "&&") condBr(a.v, lr, le) else condBr(a.v, le, lr)
                startBlock(lr)
                val b = coerce(genExpr(e.r), Ty.I1)
                val bFrom = cur
                br(le)
                startBlock(le)
                return Val(emitT("phi i1 [ ${if (e.op == "&&") "false" else "true"}, %$from ], [ ${b.v}, %$bFrom ]"), Ty.I1)
            }
            "?:" -> {
                val a = genExpr(e.l)
                if (a.t.llvm != "ptr") return a
                val from = cur
                val lr = label("elvis.rhs"); val le = label("elvis.end")
                condBr(emitT("icmp ne ptr ${a.v}, null"), le, lr)
                startBlock(lr)
                val b0 = genExpr(e.r)
                val res = ArrayList<Pair<Val, String>>()
                res.add(a to from)
                if (!term) { val b = coerce(b0, a.t); res.add(b to cur); br(le) }
                startBlock(le)
                return if (res.size == 1) a else Val(emitT("phi ptr [ ${a.v}, %$from ], [ ${res[1].first.v}, %${res[1].second} ]"), a.t)
            }
            "..", "until" -> fail("ranges are only supported in for loops")
        }
        val a = genExpr(e.l); val b = genExpr(e.r)
        if (a.t == Ty.Char || b.t == Ty.Char) return charOp(e.op, a, b)
        return when (e.op) {
            "==", "!=", "<", ">", "<=", ">=" -> compare(e.op, a, b)
            "+" -> if (a.t == Ty.Str) concat(a, b) else arith(e.op, a, b)
            "-", "*", "/", "%" -> arith(e.op, a, b)
            "and", "or", "xor" -> {
                if (a.t == Ty.I1 && b.t == Ty.I1) return Val(emitT("${e.op} i1 ${a.v}, ${b.v}"), Ty.I1)
                if (!a.t.isInt || !b.t.isInt || a.t != b.t && (a.t.rank < 2 || b.t.rank < 2)) fail("'${e.op}' needs matching Int/Long operands")
                val t = if (a.t.rank >= b.t.rank) a.t else b.t
                Val(emitT("${e.op} ${t.llvm} ${coerce(a, t).v}, ${coerce(b, t).v}"), t)
            }
            "shl", "shr", "ushr" -> {
                if (!a.t.isInt) fail("'${e.op}' on ${a.t}")
                val t = if (a.t == Ty.I64) Ty.I64 else Ty.I32
                val x = coerce(a, t)
                val cnt = emitT("and ${t.llvm} ${coerce(b, t).v}, ${if (t == Ty.I64) 63 else 31}")
                val op = mapOf("shl" to "shl", "shr" to "ashr", "ushr" to "lshr").getValue(e.op)
                Val(emitT("$op ${t.llvm} ${x.v}, $cnt"), t)
            }
            else -> fail("unknown operator ${e.op}")
        }
    }

    private fun charOp(op: String, a: Val, b: Val): Val {
        val asInt = { v: Val -> if (v.t == Ty.Char) Val(v.v, Ty.I32) else v }
        return when {
            op in setOf("==", "!=", "<", ">", "<=", ">=") && a.t == Ty.Char && b.t == Ty.Char -> compare(op, asInt(a), asInt(b))
            op == "-" && a.t == Ty.Char && b.t == Ty.Char -> arith("-", asInt(a), asInt(b))
            (op == "+" || op == "-") && a.t == Ty.Char && b.t.isInt -> Val(arith(op, asInt(a), coerce(b, Ty.I32)).v, Ty.Char)
            op == "+" && a.t == Ty.Str -> concat(a, b)
            else -> fail("operator $op on ${a.t} and ${b.t}")
        }
    }

    private fun concat(a: Val, b: Val): Val {
        val sb = emitT("call ptr @rt_sb_new()")
        appendVal("rt_sb_", "ptr $sb, ", a); appendVal("rt_sb_", "ptr $sb, ", b)
        return Val(emitT("call ptr @rt_sb_done(ptr $sb)"), Ty.Str)
    }

    private fun numType(a: Val, b: Val): Ty {
        if (!a.t.isNum || !b.t.isNum) fail("operator on non-numeric types ${a.t}, ${b.t}")
        val r = maxOf(a.t.rank, b.t.rank, 2)
        val u = a.t.isUnsigned || b.t.isUnsigned
        return when (r) { 2 -> if (u) Ty.U32 else Ty.I32; 3 -> if (u) Ty.U64 else Ty.I64; else -> Ty.F64 }
    }

    private fun arith(op: String, a: Val, b: Val): Val {
        val t = numType(a, b)
        val x = coerce(a, t).v; val y = coerce(b, t).v
        val ins = if (t == Ty.F64) mapOf("+" to "fadd", "-" to "fsub", "*" to "fmul", "/" to "fdiv", "%" to "frem")
        else if (t.isUnsigned) mapOf("+" to "add", "-" to "sub", "*" to "mul", "/" to "udiv", "%" to "urem")
        else mapOf("+" to "add", "-" to "sub", "*" to "mul", "/" to "sdiv", "%" to "srem")
        return Val(emitT("${ins.getValue(op)} ${t.llvm} $x, $y"), t)
    }

    private fun compare(op: String, a: Val, b: Val): Val {
        if (a.t == Ty.I1 && b.t == Ty.I1 && (op == "==" || op == "!=")) return Val(emitT("icmp ${if (op == "==") "eq" else "ne"} i1 ${a.v}, ${b.v}"), Ty.I1)
        // `null` vs a non-nullable primitive never matches (kotlinc only warns).
        if ((a.v == "null" && b.t.llvm != "ptr") || (b.v == "null" && a.t.llvm != "ptr")) {
            if (op != "==" && op != "!=") fail("cannot order null")
            return Val(if (op == "==") "false" else "true", Ty.I1)
        }
        if (a.t.llvm == "ptr" || b.t.llvm == "ptr") {
            if (op != "==" && op != "!=") fail("cannot order references")
            if (a.t == Ty.Str && b.t == Ty.Str) {
                val c = emitT("call i32 @rt_str_eq(ptr ${a.v}, ptr ${b.v})") // null-safe
                return Val(emitT("icmp ${if (op == "==") "ne" else "eq"} i32 $c, 0"), Ty.I1)
            }
            return Val(emitT("icmp ${if (op == "==") "eq" else "ne"} ptr ${a.v}, ${b.v}"), Ty.I1)
        }
        val t = numType(a, b)
        val x = coerce(a, t).v; val y = coerce(b, t).v
        val res = if (t == Ty.F64) {
            val c = mapOf("==" to "oeq", "!=" to "une", "<" to "olt", ">" to "ogt", "<=" to "ole", ">=" to "oge").getValue(op)
            emitT("fcmp $c double $x, $y")
        } else {
            val c = if (t.isUnsigned) mapOf("==" to "eq", "!=" to "ne", "<" to "ult", ">" to "ugt", "<=" to "ule", ">=" to "uge").getValue(op)
            else mapOf("==" to "eq", "!=" to "ne", "<" to "slt", ">" to "sgt", "<=" to "sle", ">=" to "sge").getValue(op)
            emitT("icmp $c ${t.llvm} $x, $y")
        }
        return Val(res, Ty.I1)
    }
}

private val PRELUDE = """
target triple = "arm64-apple-macosx14.0.0"
@rt_argc = global i32 0
declare ptr @malloc(i64)
declare void @free(ptr)
declare i64 @strlen(ptr)
declare i32 @strcmp(ptr, ptr)
declare ptr @getenv(ptr)
declare ptr @fopen(ptr, ptr)
declare i32 @fseek(ptr, i64, i32)
declare i64 @ftell(ptr)
declare i64 @fread(ptr, i64, i64, ptr)
declare i32 @fclose(ptr)
declare i64 @rt_nanos()
declare void @rt_panic(ptr) noreturn
declare void @rt_exit_flush()
declare void @rt_print_str(ptr)
declare void @rt_print_i64(i64)
declare void @rt_print_f64(double)
declare void @rt_print_bool(i1 zeroext)
declare void @rt_newline()
declare ptr @rt_sb_new()
declare void @rt_sb_str(ptr, ptr)
declare void @rt_sb_i64(ptr, i64)
declare void @rt_sb_f64(ptr, double)
declare void @rt_sb_bool(ptr, i1 zeroext)
declare ptr @rt_sb_done(ptr)
declare ptr @rt_list_new(ptr)
declare void @rt_list_add(ptr, i64)
declare i64 @rt_list_get(ptr, i64)
declare i64 @rt_list_set(ptr, i64, i64)
declare i64 @rt_list_size(ptr)
declare void @rt_list_release(ptr)
declare ptr @rt_arr_new(i64, i64)
declare void @rt_oob(i64, i64) noreturn cold
declare void @rt_print_char(i32)
declare void @rt_print_u64(i64)
declare void @rt_sb_u64(ptr, i64)
declare i32 @rt_str_eq(ptr, ptr)
declare void @rt_sb_char(ptr, i32)
declare ptr @rt_sb_tostring(ptr)
declare i64 @rt_sb_len(ptr)
declare void @rt_sb_release(ptr)
declare ptr @rt_map_new()
declare void @rt_map_put(ptr, i64, i64)
declare i64 @rt_map_get(ptr, i64)
declare i64 @rt_map_get_or(ptr, i64, i64)
declare i32 @rt_map_contains(ptr, i64)
declare i64 @rt_map_size(ptr)
declare void @rt_map_release(ptr)
declare double @llvm.sqrt.f64(double)
declare double @llvm.fabs.f64(double)
!0 = !{!"branch_weights", i32 2000, i32 1}

define internal void @rt_release_dyn(ptr %p) {
entry:
  %isnull = icmp eq ptr %p, null
  br i1 %isnull, label %done, label %go
go:
  %vtp = getelementptr i8, ptr %p, i64 8
  %vt = load ptr, ptr %vtp
  %fn = load ptr, ptr %vt
  call void %fn(ptr %p)
  br label %done
done:
  ret void
}
declare ptr @rt_alloc(i64)
declare void @rt_free(ptr, i64)

define internal void @rt_retain(ptr %p) alwaysinline {
entry:
  %isnull = icmp eq ptr %p, null
  br i1 %isnull, label %done, label %inc
inc:
  %c = load i64, ptr %p
  %c1 = add i64 %c, 1
  store i64 %c1, ptr %p
  br label %done
done:
  ret void
}

"""
