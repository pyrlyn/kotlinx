package newkotlin

/** An SSA value (or constant) with its type. `owned` = caller holds a +1 reference (ref types only). */
class Val(val v: String, val t: Ty, val owned: Boolean = false)

private class Local(val slot: String, val t: Ty, val isVar: Boolean)
private class Scope { val vars = HashMap<String, Local>(); val refs = ArrayList<Local>() }
private class FunSig(val name: String, val params: List<Ty>, val ret: Ty)
/** Object layout: { i64 refcount, ptr vtable, fields... }; vtable[0] = release function. */
/**
 * `fields`: inherited fields first (prefix layout), then own. `supers`: all superclasses and interfaces (transitive).
 * `ancestors`: superclass chain. `impl`: method name -> class whose body implements it (absent = abstract).
 */
private class ClassInfo(val name: String, val fields: List<Pair<String, Ty>>, val supers: List<String> = emptyList(), val methods: Map<String, FunD> = emptyMap(),
                        val ancestors: List<String> = emptyList(), val impl: Map<String, String> = methods.keys.associateWith { name }) {
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

/** vtable entries before the interface-method slots (see genClass). */
private const val VT_HEADER = 5

/** Native target: typed LLVM IR generation (linked by clang with runtime/runtime.c). */
/** Thrown when code needs an instance of a generic class that is not in the program yet; [compileGenerics] adds it and retries. */
class NeedInstance(val name: String, val args: List<TypeRef>) : Exception()

class NativeBackend(private val prog: Program, private val genericClasses: Map<String, ClassD> = emptyMap(),
                    /** Instance class name -> (generic class, type arguments), for inference against `Box<T>` parameters. */
                    private val instOrigin: Map<String, Pair<String, List<TypeRef>>> = emptyMap()) {
    private val globals = StringBuilder()
    private val funcs = StringBuilder()
    private val strMap = HashMap<String, String>()
    private val classes = HashMap<String, ClassInfo>()
    private val sigs = HashMap<String, FunSig>()
    private val ifaces = HashMap<String, IfaceInfo>()
    private val methodSigs = HashMap<String, FunSig>() // "Class.method"
    private val slots = ArrayList<String>()             // interface method name -> vtable slot (index + VT_HEADER)
    private var owner: ClassInfo? = null
    /** Generic functions by name; each call site instantiates (monomorphizes) one copy per type-argument list. */
    private val genericFuns = HashMap<String, FunD>()
    /** Instance symbol -> (declaration, bindings, signature); `pending` are instances not generated yet. */
    private val instances = HashMap<String, Triple<FunD, Map<String, Ty>, FunSig>>()
    private val pending = ArrayList<String>()
    /** Receiver class of generic method instances (absent for top-level generic functions). */
    private val instCls = HashMap<String, ClassInfo>()
    /** Type-parameter bindings of the generic instance being generated. */
    private var tenv: Map<String, Ty> = emptyMap()

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
        // Immortal string object (writable: retain/release touch the count): { refcount = 2^62, never reaches 0; chars }.
        globals.append("$name = private global { i64, [${bytes.size + 1} x i8] } { i64 4611686018427387904, [${bytes.size + 1} x i8] c\"$enc\\00\" }, align 8\n")
        "getelementptr inbounds ({ i64, [${bytes.size + 1} x i8] }, ptr $name, i32 0, i32 1)"
    }

    // ---------------------------------------------------------------- types

    private fun resolve(t: TypeRef): Ty = tenv[t.name]?.also { if (t.args.isNotEmpty()) fail("type parameter ${t.name} takes no type arguments") } ?: when (t.name) {
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
        else -> if (t.name in genericClasses) instanceTy(t.name, t.args) else if (t.name in classes) Ty.Cls(t.name) else if (t.name in ifaces) Ty.Iface(t.name) else if (t.name.startsWith("CPointer")) Ty.RawPtr else fail("unknown type $t")
    }

    private fun mapTy(k: Ty, v: Ty): Ty {
        if (!(k.isInt || k == Ty.Char)) fail("HashMap keys must be Int/Long/Char, found $k")
        if (v.llvm == "ptr" || v == Ty.Unit) fail("HashMap values must be primitives, found $v")
        return Ty.MapTy(k, v)
    }

    private fun releaseFn(t: Ty): String? = when (t) {
        is Ty.Cls -> if (t.name in poly) "@rt_release_dyn" else "@rel.${t.name}"
        is Ty.ListTy -> "@rt_list_release"
        is Ty.MapTy -> "@rt_map_release"
        is Ty.Iface -> "@rt_release_dyn"
        Ty.SB -> "@rt_sb_release"
        Ty.Str -> "@rt_str_release"
        is Ty.Arr -> "@rt_arr_release"
        else -> null
    }

    private fun retain(v: String, t: Ty) = emit("call void @${if (t.hasPrefixRc) "rt_hdr_retain" else "rt_retain"}(ptr $v)")
    private fun release(v: Val) { releaseFn(v.t)?.let { emit("call void $it(ptr ${v.v})") } }
    private fun releaseIfOwned(v: Val) { if (v.owned && v.t.isRef) release(v) }
    /** Turns a value into a +1 reference that can be stored. */
    private fun consume(v: Val): Val {
        if (!v.t.isRef || v.owned) return v
        retain(v.v, v.t); return Val(v.v, v.t, true)
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
        if (v.t == Ty.RawPtr && t == Ty.Str) return Val(emitT("call ptr @rt_str_from_c(ptr ${v.v})"), Ty.Str, true)
        if ((v.t == Ty.RawPtr || v.t == Ty.Str) && (t == Ty.RawPtr || t == Ty.Str)) return Val(v.v, t, v.owned)
        if (v.v == "null" && t.llvm == "ptr") return Val("null", t)
        if (v.t is Ty.Cls && t is Ty.Iface && t.name in classes.getValue(v.t.name).supers) return Val(v.v, t, v.owned)
        if (v.t is Ty.Cls && t is Ty.Cls && t.name in classes.getValue(v.t.name).ancestors) return Val(v.v, t, v.owned)
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
        for (c in classOrder) {
            line = c.line
            val bi = c.superClass?.let { s -> if (s !in classDecls) fail("unknown superclass $s"); classes.getValue(s) }
            for (sup in c.supers.drop(if (bi != null) 1 else 0)) if (sup !in ifaces) fail("unknown interface $sup")
            val fields = ArrayList(bi?.fields ?: emptyList())
            for (f in c.fields) {
                if (fields.any { it.first == f.name }) fail("property '${f.name}' of ${c.name} hides an inherited property")
                fields.add(f.name to (f.type?.let { resolve(it) } ?: Ty.Unit))
            }
            val methods = HashMap(bi?.methods ?: emptyMap()); val impl = HashMap(bi?.impl ?: emptyMap())
            for (m in c.methods) { methods[m.name] = m; if (m.body != null || m.exprBody != null) impl[m.name] = c.name else impl.remove(m.name) }
            classes[c.name] = ClassInfo(c.name, fields, (c.supers + (bi?.supers ?: emptyList())).distinct(), methods,
                if (bi != null) listOf(bi.name) + bi.ancestors else emptyList(), impl)
        }
        for (c in classes.values) for (a in c.ancestors) poly.add(a)
        for (c in classes.values) if (c.name in poly || c.ancestors.isNotEmpty()) for ((m, d) in c.methods) if (d.typeParams.isEmpty() && m !in slots) slots.add(m)
        for (c in classOrder) {
            for (m in c.methods) {
                line = m.line
                if (m.typeParams.isNotEmpty()) {
                    if (m.ret == null && m.body == null) fail("expression-bodied method '${m.name}' needs an explicit return type")
                    if (c.name in poly || c.superClass != null) fail("generic method '${m.name}' in an open class hierarchy is not supported")
                    continue
                }
                val ret = m.ret?.let { resolve(it) } ?: if (m.exprBody == null) Ty.Unit else fail("method '${m.name}' needs an explicit return type")
                val sig = FunSig(m.name, m.params.map { resolve(it.type) }, ret)
                c.superClass?.let { s -> methodSigs["$s.${m.name}"]?.let { if (it.params != sig.params || it.ret != sig.ret) fail("override of '${m.name}' in ${c.name} changes its signature") } }
                methodSigs["${c.name}.${m.name}"] = sig
            }
            val ci = classes.getValue(c.name)
            for (n in ci.methods.keys) if (ci.methods.getValue(n).typeParams.isEmpty() && "${c.name}.$n" !in methodSigs) methodSigs["${c.name}.$n"] = methodSigs.getValue("${ci.ancestors.first()}.$n")
        }
        for (f in prog.funs) {
            line = f.line
            if (f.typeParams.isNotEmpty()) {
                if (f.name in genericFuns || f.name in sigs) fail("overloads are not supported: ${f.name}")
                if (f.ret == null && f.body == null) fail("expression-bodied function '${f.name}' needs an explicit return type")
                genericFuns[f.name] = f; continue
            }
            if (f.name in genericFuns) fail("overloads are not supported: ${f.name}")
            val ret = f.ret?.let { resolve(it) } ?: if (f.body != null) Ty.Unit else fail("expression-bodied function '${f.name}' needs an explicit return type")
            if (f.name in sigs) fail("overloads are not supported: ${f.name}")
            sigs[f.name] = FunSig(f.name, f.params.map { resolve(it.type) }, ret)
        }
        // Explicitly typed top-level properties (incl. enum entries) are visible to class property inference.
        for (g in prog.globals) if (g.type != null && g.name !in globalVars) { line = g.line; globalVars[g.name] = Local("@g.${g.name}", resolve(g.type), g.isVar) }
        for (c in classOrder) {
            c.superClass?.let { s -> val fs = classes.getValue(c.name).fields as MutableList<Pair<String, Ty>>; classes.getValue(s).fields.forEachIndexed { i, f -> fs[i] = f } }
            inferFields(c)
        }
        computeCyclic()
        for (c in classes.values) genClass(c)
        val main = sigs["main"] ?: throw CompileError("no main function")
        genGlobals()
        for (f in prog.funs) if (f.typeParams.isEmpty()) genFun(f, null)
        for (c in prog.classes) for (m in c.methods) if ((m.body != null || m.exprBody != null) && m.typeParams.isEmpty()) genFun(m, classes.getValue(c.name))
        for (c in prog.classes) if (c.hasCtorBody || c.name in poly) genInit(c, classes.getValue(c.name))
        for (c in prog.classes) if (c.hasCtorBody) genCtor(c, classes.getValue(c.name))
        while (pending.isNotEmpty()) {
            val sym = pending.removeAt(pending.size - 1)
            val (f, env, sig) = instances.getValue(sym)
            tenv = env
            genFun(f, instCls[sym], sig, sym)
            tenv = emptyMap()
        }
        val sb = StringBuilder()
        sb.append("; generated by newkotlinc\n").append(PRELUDE).append(globals).append('\n').append(funcs)
        sb.append("define i32 @main(i32 %argc, ptr %argv) {\n  store i32 %argc, ptr @rt_argc\n")
        sb.append("  call void @k.init.globals()\n  call void @k.main()\n")
        // Top-level references are only released at exit under --leak-check (otherwise the process just exits).
        sb.append("  %lc = load i32, ptr @rt_leakcheck\n  %lcz = icmp eq i32 %lc, 0\n  br i1 %lcz, label %out, label %fini\nfini:\n")
        sb.append("  call void @k.fini.globals()\n  br label %out\nout:\n  call void @rt_exit_flush()\n  ret i32 0\n}\n")
        if (main.ret != Ty.Unit) throw CompileError("main must return Unit")
        return sb.toString()
    }

    /** Classes that can be part of a reference cycle (handled by the cycle collector). */
    private val cyclic = HashSet<String>()
    /** Region allocator for class [name]: possibly cyclic classes go to the tracing region. */
    private fun allocFn(name: String) = if (name in cyclic) "@rt_alloc_trace" else "@rt_alloc"

    /**
     * A class is cyclic if it lies on a cycle of class/interface-typed fields that contains at least one
     * `var` field (with `val` fields only, a cycle cannot be built). Other classes never enter the collector.
     */
    private fun computeCyclic() {
        fun targets(t: Ty): List<String> = when (t) {
            is Ty.Cls -> classes.values.filter { it.name == t.name || t.name in it.ancestors }.map { it.name }
            is Ty.Iface -> classes.values.filter { t.name in it.supers }.map { it.name }
            else -> emptyList()
        }
        val edges = HashMap<String, List<Pair<String, Boolean>>>()
        for (c in prog.classes) {
            val ci = classes.getValue(c.name)
            edges[c.name] = generateSequence(c) { x -> x.superClass?.let { classDecls[it] } }.toList()
                .flatMap { x -> x.fields }.flatMap { f -> targets(ci.fields[ci.index(f.name)].second).map { it to f.isVar } }
        }
        fun reach(from: String): Set<String> {
            val seen = HashSet<String>(); val work = ArrayList<String>(); work.add(from)
            while (work.isNotEmpty()) { val x = work.removeAt(work.size - 1); for ((y, _) in edges[x].orEmpty()) if (seen.add(y)) work.add(y) }
            return seen
        }
        val r = classes.keys.associateWith { reach(it) }
        for ((a, es) in edges) for ((b, isVar) in es) {
            if (!isVar || (a != b && a !in r.getValue(b))) continue
            // var edge a -> b lies on a cycle: every class on a cycle through it is cyclic.
            for (c in classes.keys) if ((c == b || c in r.getValue(b)) && (c == a || a in r.getValue(c))) cyclic.add(c)
        }
        // A subclass carries its superclasses' fields, so it is cyclic whenever one of them is.
        for (c in classes.values) if (c.ancestors.any { it in cyclic }) cyclic.add(c.name)
    }

    private fun isTraced(t: Ty) = t is Ty.Cls || t is Ty.Iface

    private fun genClass(c: ClassInfo) {
        globals.append("${c.llType} = type { i64, ptr${c.fields.joinToString("") { ", " + it.second.llvm }} }\n")
        // vtable: [release, trace children, release untraced fields, free memory, is-cyclic, interface methods...]
        val vt = listOf("ptr @rel.${c.name}", "ptr @trav.${c.name}", "ptr @relo.${c.name}", "ptr @free.${c.name}",
            "ptr ${if (c.name in cyclic) "inttoptr (i64 1 to ptr)" else "null"}") +
            slots.map { m -> c.impl[m]?.let { "ptr @m.$it.$m" } ?: "ptr null" }
        globals.append("@vt.${c.name} = internal constant [${vt.size} x ptr] [${vt.joinToString(", ")}]\n")
        val f = StringBuilder()
        val cyc = c.name in cyclic
        // @rel.C is a tiny alwaysinline fast path (null check, decrement, count test; for cyclic classes also
        // purple + buffered test), so every call site inlines it. The rare cases go out of line:
        // count zero -> @relz.C (release fields, free), cyclic count non-zero and not yet buffered ->
        // rt_cc_possible_root. Header word layout: low 32 bits count, bits 32-33 color, bit 34 buffered (runtime.c).
        f.append("define internal void @rel.${c.name}(ptr %p) alwaysinline {\nentry:\n")
        f.append("  %isnull = icmp eq ptr %p, null\n  br i1 %isnull, label %done, label %dec\n")
        if (cyc) {
            f.append("dec:\n  %c = load i64, ptr %p\n  %c1 = sub i64 %c, 1\n  %cnt = and i64 %c1, 4294967295\n  %z = icmp eq i64 %cnt, 0\n  br i1 %z, label %zero, label %keep\n")
            f.append("keep:\n  %c2 = or i64 %c1, 12884901888\n  store i64 %c2, ptr %p\n  %buf = and i64 %c1, 17179869184\n  %isbuf = icmp ne i64 %buf, 0\n  br i1 %isbuf, label %done, label %root\n")
            f.append("root:\n  store i64 %c1, ptr %p\n  call void @rt_cc_possible_root(ptr %p)\n  br label %done\n")
            // Publish the zero count before releasing children (a release may run the collector).
            f.append("zero:\n  store i64 %c1, ptr %p\n  call void @relz.${c.name}(ptr %p)\n  br label %done\n")
        } else {
            f.append("dec:\n  %c = load i64, ptr %p\n  %c1 = sub i64 %c, 1\n  %z = icmp eq i64 %c1, 0\n  br i1 %z, label %zero, label %keep\n")
            f.append("keep:\n  store i64 %c1, ptr %p\n  br label %done\n")
            f.append("zero:\n  call void @relz.${c.name}(ptr %p)\n  br label %done\n")
        }
        f.append("done:\n  ret void\n}\n\n")
        f.append("define internal void @relz.${c.name}(ptr %p) noinline {\nentry:\n")
        c.fields.forEachIndexed { i, (_, t) ->
            releaseFn(t)?.let { fn ->
                f.append("  %f$i = getelementptr ${c.llType}, ptr %p, i32 0, i32 ${i + 2}\n  %v$i = load ptr, ptr %f$i\n  call void $fn(ptr %v$i)\n")
            }
        }
        f.append("  %szp = getelementptr ${c.llType}, ptr null, i32 1\n  %sz = ptrtoint ptr %szp to i64\n")
        relfFields(c)
        // Inline rt_cc_dead: a buffered object is colored black and left to the collector; otherwise freed here.
        if (cyc) f.append("  %d = load i64, ptr %p\n  %dbuf = and i64 %d, 17179869184\n  %dead = icmp ne i64 %dbuf, 0\n  br i1 %dead, label %blk, label %fr\n" +
            "blk:\n  %d2 = and i64 %d, -12884901889\n  store i64 %d2, ptr %p\n  ret void\n" +
            "fr:\n  call void @rt_free_trace(ptr %p, i64 %sz)\n  ret void\n}\n\n")
        else f.append("  call void @rt_free(ptr %p, i64 %sz)\n  ret void\n}\n\n")
        // Collector hooks: trace class/interface children; release other reference fields; free memory only.
        f.append("define internal void @trav.${c.name}(ptr %p, ptr %fn) {\nentry:\n")
        c.fields.forEachIndexed { i, (_, t) ->
            if (isTraced(t)) f.append("  %f$i = getelementptr ${c.llType}, ptr %p, i32 0, i32 ${i + 2}\n  %v$i = load ptr, ptr %f$i\n  call void %fn(ptr %v$i)\n")
        }
        f.append("  ret void\n}\n\ndefine internal void @relo.${c.name}(ptr %p) {\nentry:\n")
        c.fields.forEachIndexed { i, (_, t) ->
            if (!isTraced(t)) releaseFn(t)?.let { fn -> f.append("  %f$i = getelementptr ${c.llType}, ptr %p, i32 0, i32 ${i + 2}\n  %v$i = load ptr, ptr %f$i\n  call void $fn(ptr %v$i)\n") }
        }
        f.append("  ret void\n}\n\ndefine internal void @free.${c.name}(ptr %p) {\nentry:\n")
        f.append("  %szp = getelementptr ${c.llType}, ptr null, i32 1\n  %sz = ptrtoint ptr %szp to i64\n  call void ${if (cyc) "@rt_free_trace" else "@rt_free"}(ptr %p, i64 %sz)\n  ret void\n}\n\n")
        funcs.append(f)
    }

    /** @relf.C: releases the reference fields of a dying C whose memory is reused (FBIP). */
    private fun relfFields(c: ClassInfo) {
        val f = StringBuilder("define internal void @relf.${c.name}(ptr %p) {\nentry:\n")
        c.fields.forEachIndexed { i, (_, t) ->
            releaseFn(t)?.let { fn -> f.append("  %f$i = getelementptr ${c.llType}, ptr %p, i32 0, i32 ${i + 2}\n  %v$i = load ptr, ptr %f$i\n  call void $fn(ptr %v$i)\n") }
        }
        f.append("  ret void\n}\n\n")
        funcs.append(f)
    }

    private fun genFun(f: FunD, cls: ClassInfo?, instSig: FunSig? = null, instSym: String? = null) {
        if (cls != null && f.body == null && f.exprBody == null) fail("abstract method ${f.name} in class ${cls.name}")
        val sig = instSig ?: if (cls != null) methodSigs.getValue("${cls.name}.${f.name}") else sigs.getValue(f.name)
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
        val name = if (instSym != null) instSym else if (cls != null) "m.${cls.name}.${f.name}" else if (f.name == "main") "k.main" else "k.${f.name}"
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

    private fun lookup(n: String): Local? { for (i in scopes.indices.reversed()) scopes[i].vars[n]?.let { return it }; return globalVars[n] }

    /** Top-level properties: module globals, initialized in declaration order by @k.init.globals before main. */
    private val globalVars = HashMap<String, Local>()
    private val seenGlobals = HashSet<String>()
    private fun genGlobals() {
        owner = null
        allocas = StringBuilder(); body = StringBuilder(); tmpN = 0; lblN = 0; cur = "entry"; term = false
        scopes.clear(); retTy = Ty.Unit
        scopes.add(Scope())
        for (g in prog.globals) {
            line = g.line
            if (!seenGlobals.add(g.name)) fail("duplicate top-level property '${g.name}'")
            val v0 = genArg(g.init)
            if (v0.t == Ty.Nothing) fail("top-level property '${g.name}' never completes")
            val t = g.type?.let { resolve(it) } ?: v0.t
            if (t == Ty.Unit || t == Ty.Args) fail("cannot store $t in top-level property '${g.name}'")
            var v = coerce(v0, t)
            if (t.isRef) v = consume(v)
            val slot = "@g.${g.name}"
            globals.append("$slot = internal global ${t.llvm} zeroinitializer\n")
            emit("store ${t.llvm} ${v.v}, ptr $slot")
            globalVars[g.name] = Local(slot, t, g.isVar)
        }
        releaseAll(); emit("ret void")
        funcs.append("define internal void @k.init.globals() {\nentry:\n").append(allocas).append(body).append("}\n\n")
        // At exit, top-level references are released in reverse order (so leak checks see zero live objects).
        funcs.append("define internal void @k.fini.globals() {\nentry:\n")
        var k = 0
        for (g in prog.globals.reversed()) {
            val l = globalVars.getValue(g.name)
            releaseFn(l.t)?.let { fn -> funcs.append("  %g$k = load ptr, ptr ${l.slot}\n  store ptr null, ptr ${l.slot}\n  call void $fn(ptr %g$k)\n"); k++ }
        }
        funcs.append("  ret void\n}\n\n")
    }

    /** Constructor calls that may reuse the memory of a dying local (Perceus FBIP reuse). */
    private val reuseFor = HashMap<CallE, Local>()
    private val keepsThis = HashMap<String, Boolean>()
    var stackObjects = 0; var reuseSites = 0

    private val classDecls by lazy { prog.classes.associateBy { it.name } }

    private fun beginBody(cls: ClassInfo?, ret: Ty) {
        owner = cls
        allocas = StringBuilder(); body = StringBuilder(); tmpN = 0; lblN = 0; cur = "entry"; term = false
        scopes.clear(); retTy = ret
        scopes.add(Scope())
    }

    /** Infers the types of body properties without an explicit type by generating (and discarding) their initializers. */
    private fun inferFields(c: ClassD) {
        if (c.fields.all { it.type != null }) return
        val ci = classes.getValue(c.name)
        val fs = ci.fields as MutableList<Pair<String, Ty>>
        val savedFuncs = funcs.length
        beginBody(ci, Ty.Unit)
        for (p in c.ctorParams) declare(p.name, resolve(p.type), false, false)
        for (f in c.inits) {
            if (f !is FieldD || f.type != null) continue
            line = f.init!!.line.takeIf { it != 0 } ?: c.line
            val t = genExpr(f.init).t
            if (t == Ty.Unit || t == Ty.Nothing || t == Ty.RawPtr || t == Ty.Args) fail("property '${f.name}' of ${c.name} needs an explicit type")
            fs[ci.index(f.name)] = f.name to t
        }
        funcs.setLength(savedFuncs)
    }

    /** Classes with subclasses: static type may hold a subclass, so release and method calls dispatch through the vtable. */
    private val poly = HashSet<String>()

    /** Classes with superclasses first. */
    private val classOrder: List<ClassD> by lazy {
        val out = LinkedHashMap<String, ClassD>()
        fun visit(c: ClassD, path: Set<String>) {
            if (c.name in out) return
            if (c.name in path) fail("cyclic inheritance involving ${c.name}")
            c.superClass?.let { s -> classDecls[s]?.let { visit(it, path + c.name) } }
            out[c.name] = c
        }
        prog.classes.forEach { visit(it, emptySet()) }
        out.values.toList()
    }

    /**
     * `@init.C(this, ctor params)`: runs the superclass initializer with the super-call arguments, stores the own
     * constructor properties, then the body property initializers and init blocks in order.
     */
    private fun genInit(c: ClassD, ci: ClassInfo) {
        beginBody(ci, Ty.Unit); line = c.line
        val params = arrayListOf("ptr %this")
        for (p in c.ctorParams) {
            val t = resolve(p.type)
            params.add("${t.llvm} %a.${p.name}")
            val l = declare(p.name, t, false, false)
            emit("store ${t.llvm} %a.${p.name}, ptr ${l.slot}")
        }
        c.superClass?.let { s ->
            val bd = classDecls.getValue(s)
            val args = c.superArgs!!
            if (args.size != bd.ctorParams.size) fail("constructor $s expects ${bd.ctorParams.size} arguments")
            val vals = args.mapIndexed { i, a -> coerce(genExpr(a), resolve(bd.ctorParams[i].type)) }
            emit("call void @init.$s(ptr %this${vals.joinToString("") { ", ${it.t.llvm} ${it.v}" }})")
            vals.forEach { releaseIfOwned(it) }
        }
        fun store(n: String, v0: Val) {
            val idx = ci.index(n); val ft = ci.fields[idx].second
            val v = consume(coerce(v0, ft))
            emit("store ${ft.llvm} ${v.v}, ptr ${emitT("getelementptr ${ci.llType}, ptr %this, i32 0, i32 ${idx + 2}")}")
        }
        for (f in c.fields) if (f.init == null) store(f.name, genArg(NameE(f.name).also { it.line = c.line }))
        for (x in c.inits) {
            if (x is FieldD) store(x.name, genArg(x.init!!))
            else {
                genBlockBody(x as Block, false)
                if (term) fail("return is not allowed in an init block of ${c.name}")
            }
        }
        releaseAll()
        emit("ret void")
        funcs.append("define internal void @init.${ci.name}(${params.joinToString(", ")}) nounwind {\nentry:\n").append(allocas).append(body).append("}\n\n")
    }

    /** Constructor function for classes with plain parameters, initializers or a superclass: returns an owned object. */
    private fun genCtor(c: ClassD, ci: ClassInfo) {
        val ps = c.ctorParams.map { "${resolve(it.type).llvm} %a.${it.name}" }
        val f = StringBuilder("define internal ptr @ctor.${ci.name}(${ps.joinToString(", ")}) nounwind {\nentry:\n")
        f.append("  %sz = ptrtoint ptr getelementptr (${ci.llType}, ptr null, i32 1) to i64\n")
        f.append("  %this = call ptr ${allocFn(ci.name)}(i64 %sz)\n  store i64 1, ptr %this\n")
        f.append("  %vt = getelementptr ${ci.llType}, ptr %this, i32 0, i32 1\n  store ptr @vt.${ci.name}, ptr %vt\n")
        ci.fields.forEachIndexed { i, (_, t) ->
            f.append("  %f$i = getelementptr ${ci.llType}, ptr %this, i32 0, i32 ${i + 2}\n  store ${t.llvm} ${if (t.llvm == "ptr") "null" else "zeroinitializer"}, ptr %f$i\n")
        }
        f.append("  call void @init.${ci.name}(ptr %this${if (ps.isEmpty()) "" else ", " + ps.joinToString(", ")})\n  ret ptr %this\n}\n\n")
        funcs.append(f)
    }

    private fun ctorClass(e: E?): ClassInfo? {
        if (e !is CallE || e.lambda != null || e.typeArgs.isNotEmpty()) return null
        val n = (e.callee as? NameE)?.n ?: return null
        if (lookup(n) != null) return null
        if (classDecls[n]?.hasCtorBody == true || n in poly) return null
        return classes[n]
    }

    /**
     * Escape analysis: `val x = C(...)` lives on the stack when C has no reference fields and x is only
     * used as the receiver of field reads/writes or of C's methods that keep `this` local, in this block.
     */
    private fun stackCandidate(s: VarS, stmts: List<S>, i: Int): Boolean {
        if (s.isVar || s.type != null) return false
        val ci = ctorClass(s.init) ?: return false
        if (ci.fields.any { it.second.isRef }) return false
        if (Uses.ofStmt(s).any { it.node.n == s.name }) return false
        val fields = ci.fields.map { it.first }.toSet()
        return Escape.allUses(stmts.subList(i + 1, stmts.size), s.name) { p, g ->
            if (p !is MemberE || p.safe) false
            else if (g is CallE && g.callee === p) g.lambda == null && ci.methods[p.n]?.let { m ->
                keepsThis.getOrPut("${ci.name}.${p.n}") { Escape.methodKeepsThis(m, fields, ci.methods.keys) }
            } == true
            else p.n in fields
        }
    }

    private fun genStackObj(s: VarS) {
        val ci = ctorClass(s.init)!!
        val e = s.init as CallE
        if (e.args.size != ci.fields.size) fail("constructor ${ci.name} expects ${ci.fields.size} arguments")
        val vals = e.args.mapIndexed { k, a -> coerce(genExpr(a), ci.fields[k].second) }
        val slot = "%so.${tmpN++}"
        allocas.append("  $slot = alloca ${ci.llType}\n")
        initObj(ci, slot, vals)
        val l = declare(s.name, Ty.Cls(ci.name), false, false)
        emit("store ptr $slot, ptr ${l.slot}")
        stackObjects++
    }

    private fun initObj(ci: ClassInfo, p: String, vals: List<Val>) {
        emit("store i64 1, ptr $p")
        emit("store ptr @vt.${ci.name}, ptr ${emitT("getelementptr ${ci.llType}, ptr $p, i32 0, i32 1")}")
        vals.forEachIndexed { i, v -> emit("store ${v.t.llvm} ${v.v}, ptr ${emitT("getelementptr ${ci.llType}, ptr $p, i32 0, i32 ${i + 2}")}") }
    }

    /**
     * FBIP reuse: if the statement's value is a constructor call and a block-local object dies in this
     * statement (all its uses are inside the constructor's arguments), the new object may take its memory.
     */
    private fun planReuse(s: S, i: Int, uses: List<List<Use>>, last: Map<String, Int>, scope: Scope) {
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
            if (uses[i].any { it.node.n == n && it.node !in inArgs }) continue
            if (uses[i].count { it.node.n == n } == 1 && uses[i].first { it.node.n == n }.let { u -> call.args.any { it === u.node } }) continue // moved instead
            reuseFor[call] = l
            return
        }
    }

    /**
     * Locals whose last use is an if/else statement are handed to both branches (Perceus "push the drop
     * into the branches"): each branch owns the reference, so a branch can move it into a field
     * (`n.prev = p`) instead of retaining it there and releasing it after the if.
     */
    private var inheritFor: IfE? = null
    private var inheritLocals: List<Pair<String, Local>> = emptyList()
    private var inheritNext: List<Pair<String, Local>>? = null

    /** NameE nodes that are the last, ownership-taking use of a local: read as a move. */
    private val moveNodes = HashSet<NameE>()

    /** Releases a local's reference and clears its slot (Perceus drop after the last use). */
    private fun dropLocal(l: Local) {
        release(Val(emitT("load ptr, ptr ${l.slot}"), l.t))
        emit("store ptr null, ptr ${l.slot}")
    }

    /** Generates an expression in an ownership-taking position; a moved local is handed over without retain. */
    private fun genArg(e: E): Val {
        if (e is NameE && e in moveNodes) {
            moveNodes.remove(e)
            val l = lookup(e.n)
            if (l != null && l.t.isRef) for (sc in scopes.reversed()) if (sc.vars[e.n] === l && sc.refs.remove(l)) {
                if (e.line != 0) line = e.line
                return Val(emitT("load ptr, ptr ${l.slot}"), l.t, true)
            }
        }
        return genExpr(e)
    }

    private fun releaseScope(s: Scope) { for (l in s.refs) release(Val(emitT("load ptr, ptr ${l.slot}"), l.t)) }
    /** Innermost-last: (continue target, break target, scope depth at loop entry). */
    private val loops = ArrayList<Triple<String, String, Int>>()
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
        val scope = scopes.last()
        inheritNext?.let { for ((n, l) in it) { scope.vars[n] = l; scope.refs.add(l) } }
        inheritNext = null
        var res = Val("", Ty.Unit)
        // Perceus: per statement, the uses of each name; a local of this block is dropped right
        // after its last use, or moved (no retain/release pair) into its last use if that use takes ownership.
        val uses = b.stmts.map { Uses.ofStmt(it) }
        val last = HashMap<String, Int>()
        uses.forEachIndexed { i, us -> for (u in us) last[u.node.n] = i }
        b.stmts.forEachIndexed { i, s ->
            if (term) return@forEachIndexed
            planReuse(s, i, uses, last, scope)
            val onStack = s is VarS && stackCandidate(s, b.stmts, i)
            val mine = ArrayList<NameE>()
            for (u in uses[i]) {
                if (!u.movable || last[u.node.n] != i || uses[i].count { it.node.n == u.node.n } != 1) continue
                val l = scope.vars[u.node.n] ?: continue
                if (l in scope.refs) { moveNodes.add(u.node); mine.add(u.node) }
            }
            if (want && i == b.stmts.lastIndex && s is ExprS) {
                line = s.line
                res = genExpr(s.e)
                if (res.t.isRef) res = consume(res)
            } else if (onStack) { line = s.line; genStackObj(s as VarS) } else {
                val ife = ((s as? ExprS)?.e as? IfE)?.takeIf { it.el != null }
                if (ife != null) {
                    inheritFor = ife
                    inheritLocals = last.filter { it.value == i }.keys.mapNotNull { n -> scope.vars[n]?.takeIf { it in scope.refs }?.let { n to it } }
                }
                genStmt(s)
            }
            moveNodes.removeAll(mine)
            if (!term) for ((n, li) in last) {
                if (li != i) continue
                val l = scope.vars[n] ?: continue
                if (scope.refs.remove(l)) dropLocal(l)
            }
        }
        if (!term) releaseScope(scopes.last())
        scopes.removeAt(scopes.size - 1)
        return res
    }

    private fun genStmt(s: S) {
        line = s.line
        when (s) {
            is VarS -> {
                val v0 = genArg(s.init)
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
                loops.add(Triple(lc, le, scopes.size))
                genBlockBody(s.body, false)
                loops.removeAt(loops.size - 1)
                if (!term) br(lc)
                startBlock(le)
            }
            is ForS -> genFor(s)
            is RetS -> {
                if (s.e == null) { if (retTy != Ty.Unit) fail("missing return value"); releaseAll(); emit("ret void"); term = true }
                else emitReturn(genArg(s.e))
            }
            is ExprS -> releaseIfOwned(genExpr(s.e))
            is JumpS -> {
                val (lc, le, depth) = loops.lastOrNull() ?: fail("'${if (s.isBreak) "break" else "continue"}' outside a loop")
                for (k in scopes.size - 1 downTo depth) releaseScope(scopes[k])
                br(if (s.isBreak) le else lc)
            }
        }
    }

    private fun assign(target: E, value: E) {
        when (target) {
            is NameE -> {
                val l = lookup(target.n)
                if (l == null && owner != null && owner!!.index(target.n) >= 0) return assign(MemberE(NameE("this"), target.n, false), value)
                if (l == null) fail("unknown variable '${target.n}'")
                if (!l.isVar) fail("val '${target.n}' cannot be reassigned")
                var v = coerce(genArg(value), l.t)
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
                var v = coerce(genArg(value), ft)
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
                        val v = consume(coerce(genArg(value), rt.elem))
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
        val rng = (if (it is BinE && it.op == "step") it.l else it) as? BinE
        if (rng != null && rng.op in setOf("..", "until", "downTo")) {
            // Progression: lo..hi, lo until hi, hi downTo lo, each optionally `step n` (n > 0).
            val down = rng.op == "downTo"
            val lo0 = genExpr(rng.l); val hi0 = genExpr(rng.r)
            val t = if (lo0.t == Ty.Char && hi0.t == Ty.Char) Ty.Char else if (lo0.t == Ty.I64 || hi0.t == Ty.I64) Ty.I64 else Ty.I32
            val at = if (t == Ty.Char) Ty.I32 else t
            val lo = if (t == Ty.Char) lo0 else coerce(lo0, t); val hi = if (t == Ty.Char) hi0 else coerce(hi0, t)
            val stepV = if (it !== rng) {
                val sv = coerce(genExpr((it as BinE).r), at)
                val bad = label("step.bad"); val ok = label("step.ok")
                condBr(emitT("icmp sle ${at.llvm} ${sv.v}, 0"), bad, ok)
                startBlock(bad)
                emit("call void @rt_bad_step(i64 ${coerce(sv, Ty.I64).v})"); emit("unreachable"); term = true
                startBlock(ok)
                sv.v
            } else "1"
            val idx = declare(s.name, t, false, false)
            emit("store ${t.llvm} ${lo.v}, ptr ${idx.slot}")
            val cmp = when (rng.op) { ".." -> "sle"; "until" -> "slt"; else -> "sge" }
            condBr(emitT("icmp $cmp ${t.llvm} ${lo.v}, ${hi.v}"), lb, le)
            // Inclusive last bound (lo < hi holds for `until` here, so hi - 1 cannot overflow).
            startBlock(lb)
            loops.add(Triple(ls, le, scopes.size))
            genBlockBody(s.body, false)
            loops.removeAt(loops.size - 1)
            startBlock(ls)
            val last = if (rng.op == "until") emitT("sub ${t.llvm} ${hi.v}, 1") else hi.v
            val i2 = emitT("load ${t.llvm}, ptr ${idx.slot}")
            // Remaining distance to the bound (unsigned: never overflows); stop if the next step passes it.
            val rem = if (down) emitT("sub ${t.llvm} $i2, $last") else emitT("sub ${t.llvm} $last, $i2")
            val done = if (stepV == "1") emitT("icmp eq ${t.llvm} $rem, 0") else emitT("icmp ult ${t.llvm} $rem, $stepV")
            val inc = label("for.inc")
            condBr(done, le, inc)
            startBlock(inc)
            emit("store ${t.llvm} ${emitT("${if (down) "sub" else "add"} ${t.llvm} $i2, $stepV")}, ptr ${idx.slot}")
            br(lb)
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
            loops.add(Triple(ls, le, scopes.size))
            genBlockBody(s.body, false)
            loops.removeAt(loops.size - 1)
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
                val inh = if (e === inheritFor) inheritLocals else emptyList()
                if (e === inheritFor) { inheritFor = null; inheritLocals = emptyList() }
                val c = coerce(genExpr(e.cond), Ty.I1)
                if (inh.isNotEmpty()) for (sc in scopes.reversed()) for ((_, l) in inh) sc.refs.remove(l)
                val lt = label("if.then"); val lf = label("if.else"); val le = label("if.end")
                condBr(c.v, lt, lf)
                val res = ArrayList<Pair<Val, String>>()
                startBlock(lt)
                if (inh.isNotEmpty()) inheritNext = inh
                val a = genBlockBody(e.th, e.el != null)
                if (!term) { res.add(a to cur); br(le) }
                startBlock(lf)
                if (inh.isNotEmpty()) inheritNext = inh
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
        var t = res[0].first.t
        if (exhaustive && res.any { it.first.t != t }) commonIface(res.map { it.first.t })?.let { t = it }
        if (!exhaustive || t == Ty.Unit || res.any { it.first.t != t && !((t is Ty.Iface || t is Ty.Cls) && it.first.t is Ty.Cls) }) {
            for ((v, _) in res) if (v.t.isRef && v.owned) fail("if/when branches yield references of different types; assign the result or make the types match")
            return Val("", Ty.Unit)
        }
        if (res.size == 1) return res[0].first
        val phi = emitT("phi ${t.llvm} " + res.joinToString(", ") { "[ ${it.first.v}, %${it.second} ]" })
        return Val(phi, t, t.isRef)
    }

    /** The single interface all branch types (classes or that interface) implement, if any; Cls -> Iface is free. */
    private fun commonIface(ts: List<Ty>): Ty? {
        if (ts.any { it !is Ty.Cls && it !is Ty.Iface }) return null
        val sets = ts.map { t -> if (t is Ty.Iface) setOf(t.name) else classes.getValue((t as Ty.Cls).name).let { it.supers.toSet() + it.name } }
        val common = sets.reduce { a, b -> a intersect b }
        // Most derived common superclass first, else the single common interface.
        common.filter { it in classes }.maxByOrNull { classes.getValue(it).ancestors.size }?.let { return Ty.Cls(it) }
        return common.singleOrNull()?.let { Ty.Iface(it) }
    }

    private fun genWhen(e: WhenE): Val {
        val subj = e.subj?.let { genExpr(it) }
        val le = label("when.end")
        val res = ArrayList<Pair<Val, String>>()
        var hasElse = false
        // A subject of enum type whose branches name every entry is exhaustive: the last branch acts as `else`.
        val entries = (subj?.t as? Ty.Cls)?.let { t -> classDecls[t.name]?.enumEntries?.map { "${t.name}$${it.first}" } }
        val exhaustive = entries != null && e.branches.none { it.first == null } &&
            e.branches.flatMap { b -> b.first!!.map { (it as? NameE)?.n } }.containsAll(entries)
        for ((bi, br0) in e.branches.withIndex()) {
            val (conds0, blk) = br0
            val conds = if (exhaustive && bi == e.branches.size - 1) null else conds0
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
        return Val(emitT("call ptr @rt_sb_done(ptr $sb)"), Ty.Str, true)
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
            v.t is Ty.Cls && classDecls[v.t.name]?.enumEntries != null -> {
                val ci = classes.getValue(v.t.name)
                val np = emitT("load ptr, ptr ${emitT("getelementptr ${ci.llType}, ptr ${v.v}, i32 0, i32 2")}")
                emit("call void @${prefix}str($lead ptr $np)")
            }
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
        // User-declared functions and classes shadow the built-in ones (e.g. a user `fun check`).
        if (n !in sigs && n !in classes) when (n) {
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
                return Val(emitT("call ptr @rt_arr_new(i64 ${size.v}, i64 ${et.size})"), Ty.Arr(et), true)
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
            "ArrayList", "mutableListOf", "arrayListOf", "listOf" -> {
                if (n == "ArrayList" && e.args.isNotEmpty()) fail("ArrayList(capacity) is not supported")
                val vals = e.args.map { genArg(it) }
                val et = e.typeArgs.singleOrNull()?.let { resolve(it) } ?: vals.firstOrNull()?.t ?: fail("$n needs an explicit element type, e.g. $n<Long>()")
                val rel = releaseFn(et) ?: "null"
                val l = emitT("call ptr @rt_list_new(ptr $rel)")
                for (v in vals) emit("call void @rt_list_add(ptr $l, i64 ${toSlot(consume(coerce(v, et)))})")
                return Val(l, Ty.ListTy(et), true)
            }
            "ByteArray" -> {
                val size = coerce(genExpr(e.args.single()), Ty.I64)
                return Val(emitT("call ptr @rt_arr_new(i64 ${size.v}, i64 1)"), Ty.Arr(Ty.I8), true)
            }
        }
        POSIX[n]?.let { (ps, ret) ->
            if (ps.size != e.args.size) fail("$n expects ${ps.size} arguments")
            val args = e.args.mapIndexed { i, a -> "${ps[i].llvm} ${coerce(genExpr(a), ps[i]).v}" }
            return Val(emitT("call ${ret.llvm} @$n(${args.joinToString(", ")})"), ret)
        }
        genericClasses[n]?.let { cd ->
            val ta = if (e.typeArgs.isNotEmpty()) {
                if (e.typeArgs.size != cd.typeParams.size) fail("$n expects ${cd.typeParams.size} type arguments")
                e.typeArgs
            } else {
                // Infer from the constructor argument types; the probe's IR is rolled back, the call is generated below.
                if (e.args.size != cd.ctorParams.size) fail("constructor $n expects ${cd.ctorParams.size} arguments")
                val env = LinkedHashMap<String, Ty>()
                val sb = body.length; val sa = allocas.length; val sc = cur; val st = term
                val tys = e.args.map { genExpr(it).t }
                body.setLength(sb); allocas.setLength(sa); cur = sc; term = st
                cd.ctorParams.forEachIndexed { i, p -> unify(p.type, tys[i], cd.typeParams, env) }
                cd.typeParams.map { tp -> tyRef(env[tp] ?: fail("cannot infer type parameter $tp of $n; pass it explicitly")) }
            }
            return genNamedCall((instanceTy(n, ta) as Ty.Cls).name, CallE(e.callee, emptyList(), e.args, e.lambda))
        }
        classes[n]?.let { ci ->
            val cd = classDecls.getValue(n)
            if (cd.hasCtorBody) {
                if (e.args.size != cd.ctorParams.size) fail("constructor $n expects ${cd.ctorParams.size} arguments")
                val vals = e.args.mapIndexed { i, a -> coerce(genExpr(a), resolve(cd.ctorParams[i].type)) }
                val res = Val(emitT("call ptr @ctor.$n(${vals.joinToString(", ") { "${it.t.llvm} ${it.v}" }})"), Ty.Cls(n), true)
                vals.forEach { releaseIfOwned(it) }
                return res
            }
            if (e.args.size != ci.fields.size) fail("constructor $n expects ${ci.fields.size} arguments")
            val vals = e.args.mapIndexed { i, a -> consume(coerce(genArg(a), ci.fields[i].second)) }
            val szp = emitT("getelementptr ${ci.llType}, ptr null, i32 1")
            val size = emitT("ptrtoint ptr $szp to i64")
            val tok = reuseFor.remove(e)?.takeIf { l -> scopes.any { it.vars.containsValue(l) && it.refs.remove(l) } }
            val p = if (tok == null) emitT("call ptr ${allocFn(n)}(i64 $size)") else {
                // Reuse the dying object's cell if this was its last reference, else drop it and allocate.
                reuseSites++
                val old = emitT("load ptr, ptr ${tok.slot}")
                emit("store ptr null, ptr ${tok.slot}")
                val lr = label("reuse"); val ld = label("reuse.dec"); val lf = label("reuse.fresh"); val lj = label("reuse.join")
                val isnull = emitT("icmp eq ptr $old, null")
                val chk = label("reuse.chk")
                condBr(isnull, lf, chk)
                startBlock(chk)
                val rc = emitT("load i64, ptr $old")
                condBr(emitT("icmp eq i64 $rc, 1"), lr, ld)
                startBlock(lr)
                emit("call void @relf.${(tok.t as Ty.Cls).name}(ptr $old)")
                br(lj)
                startBlock(ld)
                emit("call void ${releaseFn(tok.t)}(ptr $old)")
                br(lf)
                startBlock(lf)
                val fresh = emitT("call ptr ${allocFn(n)}(i64 $size)")
                val fromF = cur
                br(lj)
                startBlock(lj)
                emitT("phi ptr [ $old, %$lr ], [ $fresh, %$fromF ]")
            }
            initObj(ci, p, vals)
            return Val(p, Ty.Cls(n), true)
        }
        genericFuns[n]?.let { return genGenericCall(it, e) }
        val sig = sigs[n] ?: fail("unknown function '$n'")
        if (sig.params.size != e.args.size) fail("$n expects ${sig.params.size} arguments")
        val vals = e.args.mapIndexed { i, a -> coerce(genExpr(a), sig.params[i]) }
        val args = vals.filter { it.t != Ty.Args }.joinToString(", ") { "${it.t.llvm} ${it.v}" }
        val call = "call ${sig.ret.llvm} @k.$n($args)"
        val res = if (sig.ret == Ty.Unit) { emit(call); Val("", Ty.Unit) } else Val(emitT(call), sig.ret, sig.ret.isRef)
        vals.forEach { releaseIfOwned(it) }
        return res
    }

    /**
     * Call of a generic function: type arguments are explicit (`f<Int>(x)`) or inferred from the argument types;
     * the call binds to the monomorphized instance `@"k.f<Int>"`, queued for generation on first use.
     * Monomorphization keeps primitives unboxed and every instance's RC code exact for its concrete types.
     */
    private fun genGenericCall(f: FunD, e: CallE, cls: ClassInfo? = null, recv: Val? = null): Val {
        if (f.params.size != e.args.size) fail("${f.name} expects ${f.params.size} arguments")
        val env = LinkedHashMap<String, Ty>()
        if (e.typeArgs.isNotEmpty()) {
            if (e.typeArgs.size != f.typeParams.size) fail("${f.name} expects ${f.typeParams.size} type arguments")
            f.typeParams.forEachIndexed { i, tp -> env[tp] = resolve(e.typeArgs[i]) }
        }
        val raw = e.args.map { genExpr(it) }
        if (e.typeArgs.isEmpty()) f.params.forEachIndexed { i, p -> unify(p.type, raw[i].t, f.typeParams, env) }
        for (tp in f.typeParams) if (tp !in env) fail("cannot infer type parameter $tp of ${f.name}; pass it explicitly")
        val bound = f.typeParams.associateWith { env.getValue(it) }
        val sym = "\"${if (cls != null) "m.${cls.name}." else "k."}${f.name}<${f.typeParams.joinToString(",") { bound.getValue(it).toString() }}>\""
        val inst = instances[sym] ?: run {
            val saved = tenv; tenv = bound
            val sig = try {
                FunSig(f.name, f.params.map { resolve(it.type) }, f.ret?.let { resolve(it) } ?: Ty.Unit)
            } finally { tenv = saved }
            if (cls != null) instCls[sym] = cls
            Triple(f, bound, sig).also { instances[sym] = it; pending.add(sym) }
        }
        val sig = inst.third
        val vals = raw.mapIndexed { i, v -> coerce(v, sig.params[i]) }
        val args = (listOfNotNull(recv?.let { "ptr ${it.v}" }) + vals.map { "${it.t.llvm} ${it.v}" }).joinToString(", ")
        return finishCall("call ${sig.ret.llvm} @$sym($args)", sig.ret, vals)
    }

    /** `Box<Int>` -> the monomorphized class `Box$Int`; requests the instance if the program does not have it yet. */
    private fun instanceTy(n: String, args: List<TypeRef>): Ty {
        val cd = genericClasses.getValue(n)
        if (args.size != cd.typeParams.size) fail("$n expects ${cd.typeParams.size} type arguments")
        val concrete = args.map { tyRef(resolve(it)) }
        val m = mangle(n, concrete)
        if (m in classes) return Ty.Cls(m)
        throw NeedInstance(n, concrete)
    }

    /** Binds type parameters in [ref] against the concrete argument type [t]; the first binding wins (later args coerce). */
    private fun unify(ref: TypeRef, t: Ty, tps: List<String>, env: MutableMap<String, Ty>) {
        if (ref.name in tps) { if (ref.name !in env && t != Ty.Nothing) env[ref.name] = t; return }
        when {
            t is Ty.ListTy && ref.args.size == 1 -> unify(ref.args[0], t.elem, tps, env)
            t is Ty.MapTy && ref.args.size == 2 -> { unify(ref.args[0], t.k, tps, env); unify(ref.args[1], t.v, tps, env) }
            t is Ty.Cls -> instOrigin[t.name]?.let { (g, args) ->
                if (g == ref.name && args.size == ref.args.size) {
                    val saved = tenv; tenv = emptyMap()
                    val tys = try { args.map { resolve(it) } } finally { tenv = saved }
                    ref.args.forEachIndexed { i, a -> unify(a, tys[i], tps, env) }
                }
            }
        }
    }

    private fun method(r: Val, n: String, e: CallE): Val {
        val t = r.t
        val res: Val = when {
            t.isNum && n in setOf("toInt", "toUInt", "toLong", "toULong", "toByte", "toDouble", "toString") -> {
                if (n == "toString") { val sb = emitT("call ptr @rt_sb_new()"); appendVal("rt_sb_", "ptr $sb, ", r); return Val(emitT("call ptr @rt_sb_done(ptr $sb)"), Ty.Str, true) }
                val target = when (n) { "toInt" -> Ty.I32; "toUInt" -> Ty.U32; "toLong" -> Ty.I64; "toULong" -> Ty.U64; "toByte" -> Ty.I8; else -> Ty.F64 }
                when {
                    t == Ty.F64 && target.isInt -> Val(emitT("fptosi double ${r.v} to ${target.llvm}"), target)
                    else -> coerce(r, target)
                }
            }
            t == Ty.RawPtr && n == "toKString" -> Val(emitT("call ptr @rt_str_from_c(ptr ${r.v})"), Ty.Str, true)
            t == Ty.Str && n == "toKString" -> return r
            t == Ty.Str && n == "toString" -> return r
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
            t == Ty.SB && n == "toString" -> Val(emitT("call ptr @rt_sb_tostring(ptr ${r.v})"), Ty.Str, true)
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
                val v = consume(coerce(genArg(e.args.single()), t.elem))
                emit("call void @rt_list_add(ptr ${r.v}, i64 ${toSlot(v)})")
                Val("true", Ty.I1)
            }
            t is Ty.ListTy && n == "get" -> {
                val i = coerce(genExpr(e.args.single()), Ty.I64)
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
        ci.methods[n]?.takeIf { it.typeParams.isNotEmpty() }?.let { f ->
            val impl = ci.impl[n] ?: fail("abstract method ${ci.name}.$n() cannot be called")
            return genGenericCall(f, e, classes.getValue(impl), r)
        }
        val sig = methodSigs["${ci.name}.$n"] ?: fail("unknown method ${ci.name}.$n()")
        val args = methodArgs(sig, e)
        if (ci.name in poly) {
            val vt = emitT("load ptr, ptr ${emitT("getelementptr i8, ptr ${r.v}, i64 8")}")
            val fp = emitT("load ptr, ptr ${emitT("getelementptr ptr, ptr $vt, i64 ${slots.indexOf(n) + VT_HEADER}")}")
            return finishCall("call ${sig.ret.llvm} $fp(ptr ${r.v}${args.joinToString("") { ", ${it.t.llvm} ${it.v}" }})", sig.ret, args)
        }
        val impl = ci.impl[n] ?: fail("abstract method ${ci.name}.$n() cannot be called")
        return finishCall("call ${sig.ret.llvm} @m.$impl.$n(ptr ${r.v}${args.joinToString("") { ", ${it.t.llvm} ${it.v}" }})", sig.ret, args)
    }

    /** Interface call through the object's vtable (slot = global interface-method index + VT_HEADER). */
    private fun callIface(r: Val, ii: IfaceInfo, n: String, e: CallE): Val {
        val sig = ii.methods[n] ?: fail("unknown method ${ii.name}.$n()")
        val args = methodArgs(sig, e)
        val vt = emitT("load ptr, ptr ${emitT("getelementptr i8, ptr ${r.v}, i64 8")}")
        val fp = emitT("load ptr, ptr ${emitT("getelementptr ptr, ptr $vt, i64 ${slots.indexOf(n) + VT_HEADER}")}")
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
                if (t.elem.isRef) retain(elem.v, t.elem)
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
            "..", "until", "downTo", "step" -> fail("ranges are only supported in for loops")
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
        return Val(emitT("call ptr @rt_sb_done(ptr $sb)"), Ty.Str, true)
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
            val res = if (a.t == Ty.Str && b.t == Ty.Str) {
                val c = emitT("call i32 @rt_str_eq(ptr ${a.v}, ptr ${b.v})") // null-safe
                Val(emitT("icmp ${if (op == "==") "ne" else "eq"} i32 $c, 0"), Ty.I1)
            } else Val(emitT("icmp ${if (op == "==") "eq" else "ne"} ptr ${a.v}, ${b.v}"), Ty.I1)
            releaseIfOwned(a); releaseIfOwned(b)
            return res
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
declare void @rt_bad_step(i64) noreturn
@rt_leakcheck = external global i32
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
declare i64 @rt_list_remove_at(ptr, i64)
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
declare ptr @rt_alloc_trace(i64)
declare void @rt_free_trace(ptr, i64)
declare void @rt_hdr_retain(ptr)
declare void @rt_str_release(ptr)
declare void @rt_arr_release(ptr)
declare ptr @rt_str_from_c(ptr)
declare void @rt_cc_possible_root(ptr)
declare void @rt_cc_dead(ptr)
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
