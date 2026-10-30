package newkotlin

/** An SSA value (or constant) with its type. `owned` = caller holds a +1 reference (ref types only). */
class Val(val v: String, val t: Ty, val owned: Boolean = false)

private class Local(val slot: String, val t: Ty, val isVar: Boolean)
private class Scope { val vars = HashMap<String, Local>(); val refs = ArrayList<Local>() }
/** Lambdas synthesized for callable references, per reference node; survives code-generation restarts. */
private object RefLambdas { val map = HashMap<UnE, LambdaE>() }
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
/** Standard exception types `throw` accepts, with the qualified name printed for an uncaught exception. */
private val EXCEPTIONS = listOf("AssertionError", "Error", "Exception", "RuntimeException", "IllegalStateException",
    "IllegalArgumentException", "UnsupportedOperationException", "ArithmeticException", "IndexOutOfBoundsException",
    "NullPointerException", "ClassCastException", "NumberFormatException", "ConcurrentModificationException",
    "UninitializedPropertyAccessException")
    .associateWith { "java.lang.$it" } + mapOf("NoSuchElementException" to "java.util.NoSuchElementException")
/** Binary operators that call an `operator fun` on class/interface operands. */
private val OP_FUNS = mapOf("+" to "plus", "-" to "minus", "*" to "times", "/" to "div", "%" to "rem")

/** Native target: typed LLVM IR generation (linked by clang with runtime/runtime.c). */
/** Thrown when code needs an instance of a generic class that is not in the program yet; [compileGenerics] adds it and retries. */
class NeedInstance(val name: String, val args: List<TypeRef>) : Exception()
/** Code needs a synthesized class (a lambda's closure class); [compileGenerics] adds it and retries. */
class NeedClass(val cd: ClassD) : Exception()
/** Code needs a function-type interface `$Fn<params..., ret>`; [compileGenerics] adds it and retries. */
class NeedIface(val d: InterfaceD) : Exception()

class NativeBackend(private val prog: Program, private val genericClasses: Map<String, ClassD> = emptyMap(),
                    /** Instance class name -> (generic class, type arguments), for inference against `Box<T>` parameters. */
                    private val instOrigin: Map<String, Pair<String, List<TypeRef>>> = emptyMap(),
                    private val genericIfaces: Map<String, InterfaceD> = emptyMap()) {
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

    private fun resolve(t: TypeRef): Ty {
        if (t.name.endsWith("?")) return resolve(TypeRef(t.name.dropLast(1), t.args, true))
        val r = resolve0(t)
        // Inside generic instances `T?` stays `T` (substituted nullability is not tracked); AnyBox must exist.
        if (!t.nullable || t.name in tenv || tenv.isNotEmpty() || instLine >= 0 || boxName(r)?.let { "AnyBox$it" in classDecls } != true) return r
        return if (r != Ty.Str && boxName(r) != null && t.name !in setOf("Short", "UByte", "UShort")) Ty.NBox(r) else r
    }

    private fun resolve0(t: TypeRef): Ty = tenv[t.name]?.also { if (t.args.isNotEmpty()) fail("type parameter ${t.name} takes no type arguments") } ?: when (t.name) {
        "Int" -> Ty.I32
        "Long" -> Ty.I64
        "UInt" -> Ty.U32
        "ULong" -> Ty.U64
        "Byte" -> Ty.I8
        "UByte", "UShort" -> Ty.U32 // like Short: UInt-backed, toUByte()/toUShort() truncate
        "Short" -> Ty.I32 // Short values live in Int (Kotlin widens Short arithmetic to Int; toShort() truncates)
        "Boolean" -> Ty.I1
        "Double" -> Ty.F64
        "Float" -> Ty.F32
        "Unit" -> Ty.Unit
        "String" -> Ty.Str
        "Char" -> Ty.Char
        "ByteArray" -> Ty.Arr(Ty.I8)
        "IntArray" -> Ty.Arr(Ty.I32)
        "LongArray" -> Ty.Arr(Ty.I64)
        "DoubleArray" -> Ty.Arr(Ty.F64)
        "FloatArray" -> Ty.Arr(Ty.F32)
        "StringBuilder" -> Ty.SB
        "HashMap", "MutableMap", "Map" -> mapTy(resolve(t.args[0]), resolve(t.args[1]))
        "ArrayList", "MutableList", "List" -> Ty.ListTy(resolve(t.args.single()).also { if (it == Ty.Unit) fail("lists of Unit are not supported") })
        "\$Fn" -> fnTy(t.args.dropLast(1).map { resolve(it) }, resolve(t.args.last()))
        "\$FnR" -> fnTy(t.args.dropLast(1).map { resolve(it) }, resolve(t.args.last()), receiver = true)
        "Array" -> if (t.args.singleOrNull()?.name == "String" && mainParams) Ty.Args else Ty.ListTy(resolve(t.args.single()))
        else -> if (t.name in genericClasses || t.name in genericIfaces) instanceTy(t.name, t.args) else if (t.name in classes) Ty.Cls(t.name) else if (t.name in ifaces) Ty.Iface(t.name) else if (t.name.startsWith("CPointer")) Ty.RawPtr else fail("unknown type $t")
    }

    /** `Array<String>` is the program arguments only in main's parameter list; elsewhere `Array<T>` is a list. */
    private var mainParams = false

    private fun mapTy(k: Ty, v: Ty): Ty {
        if (!(k.isInt || k == Ty.Char)) fail("HashMap keys must be Int/Long/Char, found $k")
        if (v.llvm == "ptr" || v == Ty.Unit) fail("HashMap values must be primitives, found $v")
        return Ty.MapTy(k, v)
    }

    private fun releaseFn(t: Ty): String? = when (t) {
        is Ty.Cls -> if (t.name in poly) "@rt_release_dyn" else "@rel.${t.name}"
        is Ty.ListTy -> "@rt_list_release"
        is Ty.MapTy -> "@rt_map_release"
        is Ty.Iface, is Ty.NBox -> "@rt_release_dyn"
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

    private fun isAny(t: Ty) = t is Ty.Iface && t.name == "Any"
    private fun boxName(t: Ty): String? = when (t) {
        Ty.I32 -> "Int"; Ty.I64 -> "Long"; Ty.F64 -> "Double"; Ty.F32 -> "Float"; Ty.I1 -> "Boolean"; Ty.Char -> "Char"; Ty.Str -> "String"
        Ty.U32 -> "UInt"; Ty.U64 -> "ULong"; Ty.I8 -> "Byte"; else -> null
    }

    /** A value viewed as `Any`: objects as is, primitives and strings boxed into `AnyBox<T>` (owned). */
    private fun toAny(v: Val, t: Ty): Val {
        if (v.t is Ty.Cls && v.t.name.substringBefore('$') in genericClasses) fail("generic class ${v.t.name.substringBefore('$')} used as Any is not supported")
        // Kotlin compares callable references structurally; ours are distinct closures, so refuse them as Any.
        if (v.v in refVals) fail("callable references as Any are not supported")
        if (v.v == "null" || v.t is Ty.Cls || v.t is Ty.Iface || v.t is Ty.NBox) return Val(v.v, t, v.owned)
        val n = boxName(v.t) ?: fail("${v.t} cannot be used as Any")
        val b = withTemps(listOf(v)) { (x) -> genNamedCall("AnyBox$n", CallE(NameE("AnyBox$n").also { it.line = line }, emptyList(), listOf(x), null).also { it.line = line }) }
        return Val(b.v, t, true)
    }

    /** The primitive or string inside an `AnyBox` (borrowed unless [v] is owned, then the box is released). */
    private fun unbox(v: Val, target: Ty): Val {
        val ci = classes.getValue("AnyBox${boxName(target)}")
        val x = Val(emitT("load ${target.llvm}, ptr ${emitT("getelementptr ${ci.llType}, ptr ${v.v}, i32 0, i32 2")}"), target)
        if (!v.owned) return x
        if (target.isRef) retain(x.v, target)
        emit("call void @rt_release_dyn(ptr ${v.v})")
        return Val(x.v, target, target.isRef)
    }

    private fun anyCall(fn: String, vs: List<Val>): Val =
        withTemps(vs) { xs -> genNamedCall(fn, CallE(NameE(fn).also { it.line = line }, emptyList(), xs, null).also { it.line = line }) }

    private fun coerce(v: Val, t: Ty): Val {
        if (v.t == t) return v
        // Unreachable value (after `throw`): keep the slot well-typed.
        if (v.t == Ty.Nothing) return if (t.llvm == "void") v else Val("undef", t)
        if (isAny(t)) return toAny(v, t)
        // Into code that drops nullability (generic instances): a checked unbox, like `!!`.
        if (v.t is Ty.NBox && t !is Ty.NBox && t.llvm != "ptr" && t != Ty.Unit) return coerce(genNonNull(v), t)
        if (t is Ty.NBox && v.v != "null") {
            if (v.t is Ty.NBox) fail("type mismatch: expected $t, found ${v.t}")
            val p = coerce(v, t.inner)
            return Val(toAny(p, Ty.Iface("Any")).v, t, true)
        }
        if (v.t.isInt && t.isInt) {
            return when {
                t.llvm == v.t.llvm -> Val(v.v, t)
                t.rank > v.t.rank -> Val(emitT("${if (v.t.isUnsigned) "zext" else "sext"} ${v.t.llvm} ${v.v} to ${t.llvm}"), t)
                else -> Val(emitT("trunc ${v.t.llvm} ${v.v} to ${t.llvm}"), t)
            }
        }
        if (v.t.isInt && t.isFp) return Val(emitT("${if (v.t.isUnsigned) "uitofp" else "sitofp"} ${v.t.llvm} ${v.v} to ${t.llvm}"), t)
        if (v.t == Ty.F32 && t == Ty.F64) return Val(emitT("fpext float ${v.v} to double"), t)
        if (v.t == Ty.RawPtr && t == Ty.Str) return Val(emitT("call ptr @rt_str_from_c(ptr ${v.v})"), Ty.Str, true)
        if ((v.t == Ty.RawPtr || v.t == Ty.Str) && (t == Ty.RawPtr || t == Ty.Str)) return Val(v.v, t, v.owned)
        if (v.v == "null" && t.llvm == "ptr") return Val("null", t)
        if (v.t is Ty.Cls && t is Ty.Iface && t.name in classes.getValue(v.t.name).supers) return Val(v.v, t, v.owned)
        // Interface upcast: dispatch goes through global method slots, so the object is reused as is.
        if (v.t is Ty.Iface && t is Ty.Iface && ifaceDecls[v.t.name]?.supers?.contains(t.name) == true) return Val(v.v, t, v.owned)
        if (v.t is Ty.Cls && t is Ty.Cls && t.name in classes.getValue(v.t.name).ancestors) return Val(v.v, t, v.owned)
        fail("type mismatch: expected $t, found ${v.t}")
    }

    private fun toSlot(v: Val): String = when (v.t.llvm) {
        "ptr" -> emitT("ptrtoint ptr ${v.v} to i64")
        "i64" -> v.v
        "double" -> emitT("bitcast double ${v.v} to i64")
        "float" -> emitT("zext i32 ${emitT("bitcast float ${v.v} to i32")} to i64")
        "i1" -> emitT("zext i1 ${v.v} to i64")
        else -> emitT("sext ${v.t.llvm} ${v.v} to i64")
    }

    private fun fromSlot(s: String, t: Ty): String = when (t.llvm) {
        "ptr" -> emitT("inttoptr i64 $s to ptr")
        "i64" -> s
        "double" -> emitT("bitcast i64 $s to double")
        "float" -> emitT("bitcast i32 ${emitT("trunc i64 $s to i32")} to float")
        else -> emitT("trunc i64 $s to ${t.llvm}")
    }

    /** TBAA tags: array length, array elements and object fields never alias each other. */
    private fun tbLen() = ", !tbaa !21"
    private fun tbElem(t: String) = TB_TYS.indexOf(t).let { if (it < 0) "" else ", !tbaa !${30 + it}" }
    private fun tbField(t: String) = TB_TYS.indexOf(t).let { if (it < 0) "" else ", !tbaa !${50 + it}" }

    /** Bounds-checked element pointer of a typed array ({ i64 length; elements }). */
    private fun elemPtr(r: Val, i: Val): String {
        val et = (r.t as Ty.Arr).elem
        val len = emitT("load i64, ptr ${r.v}${tbLen()}")
        val ok = label("idx.ok"); val bad = label("idx.bad")
        emit("br i1 ${emitT("icmp ult i64 ${i.v}, $len")}, label %$ok, label %$bad, !prof !0"); term = true
        startBlock(bad)
        if (excMode) throwNew("ArrayIndexOutOfBoundsException", sbMsg("Index ", Val(i.v, Ty.I64), " out of bounds for length ", Val(len, Ty.I64)))
        else { emit("call void @rt_oob(i64 ${i.v}, i64 $len)"); emit("unreachable"); term = true }
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
            c.methods.groupBy { it.name }.values.firstOrNull { ms -> ms.map { m -> m.params.map { it.type.name } }.distinct().size > 1 }?.let { fail("overloads are not supported: ${c.name}.${it[0].name}") }
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
                if (m.ret == null && m.exprBody != null) { inferPending.add(m to c); continue }
                val ret = m.ret?.let { resolve(it) } ?: Ty.Unit
                val sig = FunSig(m.name, m.params.map { resolve(it.type) }, ret)
                c.superClass?.let { s -> methodSigs["$s.${m.name}"]?.let { if (it.params != sig.params || it.ret != sig.ret) fail("override of '${m.name}' in ${c.name} changes its signature") } }
                methodSigs["${c.name}.${m.name}"] = sig
            }
            val ci = classes.getValue(c.name)
            inheritSigs(c.name)
        }
        for (f in prog.funs) {
            line = f.line
            if (f.typeParams.isNotEmpty()) {
                if (f.name in genericFuns || f.name in sigs) fail("overloads are not supported: ${f.name}")
                // Expression-bodied generic functions without a return type: inferred per instance in genGenericCall.
                genericFuns[f.name] = f; continue
            }
            if (f.name in genericFuns) fail("overloads are not supported: ${f.name}")
            if (f.name in sigs || inferPending.any { it.second == null && it.first.name == f.name }) fail("overloads are not supported: ${f.name}")
            if (f.ret == null && f.body == null) { inferPending.add(f to null); continue }
            val ret = f.ret?.let { resolve(it) } ?: Ty.Unit
            mainParams = f.name == "main"
            sigs[f.name] = FunSig(f.name, f.params.map { resolve(it.type) }, ret)
            mainParams = false
        }
        // Explicitly typed top-level properties (incl. enum entries) are visible to class property inference.
        for (g in prog.globals) if (g.type != null && g.name !in globalVars) { line = g.line; globalVars[g.name] = Local("@g.${g.name}", resolve(g.type), g.isVar) }
        for (c in classOrder) {
            c.superClass?.let { s -> val fs = classes.getValue(c.name).fields as MutableList<Pair<String, Ty>>; classes.getValue(s).fields.forEachIndexed { i, f -> fs[i] = f } }
            inferFields(c)
        }
        probeGlobals()
        inferReturnTypes()
        probeGlobals()
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
            tenv = env; instLine = f.line; instFun = f
            genFun(f, instCls[sym], sig, sym)
            tenv = emptyMap(); instLine = -1; instFun = null
        }
        if (excMode) genUncaught()
        val sb = StringBuilder()
        sb.append("; generated by newkotlinc\n").append(PRELUDE).append(globals).append('\n').append(funcs)
        sb.append("define i32 @main(i32 %argc, ptr %argv) {\n  store i32 %argc, ptr @rt_argc\n")
        if (excMode) sb.append("  call void @k.init.globals()\n  call void @k.uncaught()\n  call void @k.main()\n  call void @k.uncaught()\n")
        else sb.append("  call void @k.init.globals()\n  call void @k.main()\n")
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
            // A closure can capture `this` while the object is being constructed (a val property holding a lambda
            // that captures it), so closure captures count like var edges: such cycles can exist.
            val closure = c.name.startsWith("\$Lam")
            edges[c.name] = generateSequence(c) { x -> x.superClass?.let { classDecls[it] } }.toList()
                .flatMap { x -> x.fields }.flatMap { f -> targets(ci.fields[ci.index(f.name)].second).map { it to (f.isVar || closure) } }
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
        // Overrides must share the value representation of the overridden signature (no boxing bridges).
        for ((m, impl) in c.impl) {
            val ms = methodSigs["$impl.$m"] ?: continue
            val supers = c.supers.mapNotNull { ifaces[it]?.methods?.get(m) } + c.ancestors.mapNotNull { methodSigs["$it.$m"] }
            for (sup in supers) if (sup.ret.llvm != ms.ret.llvm || sup.ret != ms.ret && (isAny(sup.ret) || isAny(ms.ret)) && ms.ret !is Ty.Cls && ms.ret !is Ty.Iface)
                fail("override ${c.name}.$m returns ${ms.ret} where ${sup.ret} is expected (bridges are not supported)")
        }
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
        owner = cls; tryStack.clear(); finStack.clear()
        allocas = StringBuilder(); body = StringBuilder(); tmpN = 0; lblN = 0; cur = "entry"; term = false
        scopes.clear(); retTy = sig.ret; line = f.line
        scopes.add(Scope())
        val params = ArrayList<String>()
        if (cls != null) params.add("ptr %this")
        f.params.forEachIndexed { i, p ->
            val t = sig.params[i]
            if (t == Ty.Args) { declare(p.name, t, false, false); return@forEachIndexed }
            if (t == Ty.Unit) fail("parameters of type Unit / Unit? are not supported")
            params.add("${t.llvm} %a.${p.name}")
            val l = declare(p.name, t, false, false)
            emit("store ${t.llvm} %a.${p.name}, ptr ${l.slot}")
            if (p.ctx) scopes.last().vars["\$ctx\$p${tmpN++}"] = l
        }
        if (f.exprBody != null) {
            val v = argFor(f.exprBody, retTy)
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
        // Every implicit receiver (also shadowed ones) stays reachable for context arguments as `$ctx$N`.
        if (name == "this") scopes.last().vars["\$ctx\$r${tmpN++}"] = l
        if (ownsRef && t.isRef) { allocas.append("  store ptr null, ptr $slot\n"); scopes.last().refs.add(l) }
        return l
    }

    private fun lookup(n: String): Local? { for (i in scopes.indices.reversed()) scopes[i].vars[n]?.let { return it }
        // A member property shadows a top-level one of the same name inside the class.
        val g = globalVars[n] ?: return null
        val o = owner
        return if (o != null && (o.index(n) >= 0 || "\$get\$$n" in o.methods || "\$set\$$n" in o.methods)) null else g
    }

    /**
     * Types of top-level properties without an explicit type, so functions whose return type is inferred can use them
     * (`val log = StringBuilder(); fun f(x: Int) = x.also { log.append("x") }`). Each initializer is generated and
     * rolled back; one that cannot be typed yet (it calls a function still being inferred) is retried later.
     */
    private fun probeGlobals() {
        for (g in prog.globals) {
            if (g.type != null || g.name in globalVars) continue
            val savedFuncs = funcs.length; val savedLine = line
            val t = try {
                beginBody(null, Ty.Unit); line = g.line
                genExpr(g.init).t
            } catch (e: CompileError) { null } finally { funcs.setLength(savedFuncs); line = savedLine }
            if (t == null || t == Ty.Nothing || t == Ty.Unit || t == Ty.Args || t == Ty.RawPtr) continue
            globalVars[g.name] = Local("@g.${g.name}", t, g.isVar)
        }
    }

    /** Top-level properties: module globals, initialized in declaration order by @k.init.globals before main. */
    private val globalVars = HashMap<String, Local>()
    private val seenGlobals = HashSet<String>()
    private fun genGlobals() {
        owner = null; tryStack.clear()
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
        tryStack.clear(); finStack.clear()
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

    /** Expression-bodied functions/methods without a declared return type, with their class (null for top-level). */
    private val inferPending = ArrayList<Pair<FunD, ClassD?>>()

    private fun inheritSigs(cn: String) {
        val ci = classes.getValue(cn)
        for (n in ci.methods.keys) if (ci.methods.getValue(n).typeParams.isEmpty() && "$cn.$n" !in methodSigs)
            methodSigs["${ci.ancestors.firstOrNull() ?: continue}.$n"]?.let { methodSigs["$cn.$n"] = it }
    }

    /**
     * Infers return types of expression-bodied functions without one by generating (and discarding) their bodies.
     * Repeats while progress is made, so such functions may call each other in dependency order.
     */
    private fun inferReturnTypes() {
        while (inferPending.isNotEmpty()) {
            var progress = false
            var lastErr: CompileError? = null
            for (it in ArrayList(inferPending)) {
                val (f, c) = it
                val ci = c?.let { classes.getValue(it.name) }
                val params = f.params.map { resolve(it.type) }
                val savedFuncs = funcs.length; val savedLine = line
                val t = try {
                    beginBody(ci, Ty.Unit); line = f.line
                    f.params.forEachIndexed { i, p -> declare(p.name, params[i], false, false) }
                    genExpr(f.exprBody!!).t
                } catch (e: CompileError) { lastErr = e; null } finally { funcs.setLength(savedFuncs); line = savedLine }
                if (t == null) continue
                line = f.line
                if (t == Ty.RawPtr || t == Ty.Args) fail("expression-bodied function '${f.name}' needs an explicit return type")
                val sig = FunSig(f.name, params, t)
                if (c == null) sigs[f.name] = sig
                else {
                    c.superClass?.let { s -> methodSigs["$s.${f.name}"]?.let { if (it.params != sig.params || it.ret != sig.ret) fail("override of '${f.name}' in ${c.name} changes its signature") } }
                    methodSigs["${c.name}.${f.name}"] = sig
                    for (d in classOrder) inheritSigs(d.name)
                }
                inferPending.remove(it); progress = true
            }
            if (!progress) {
                val (f, c) = inferPending.first(); line = f.line
                if (lastErr != null && inferPending.size == 1) throw lastErr!!
                fail(if (c == null) "expression-bodied function '${f.name}' needs an explicit return type" else "method '${f.name}' needs an explicit return type")
            }
        }
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
            if (t == Ty.Unit) fail("parameters of type Unit / Unit? are not supported")
            params.add("${t.llvm} %a.${p.name}")
            val l = declare(p.name, t, false, false)
            emit("store ${t.llvm} %a.${p.name}, ptr ${l.slot}")
            if (p.ctx) scopes.last().vars["\$ctx\$p${tmpN++}"] = l
        }
        c.superClass?.let { s ->
            val bd = classDecls.getValue(s)
            val args = excArgs(s, c.superArgs!!)
            if (args.size != bd.ctorParams.size) fail("constructor $s expects ${bd.ctorParams.size} arguments")
            val vals = args.mapIndexed { i, a -> coerce(genExpr(a), resolve(bd.ctorParams[i].type)) }
            emit("call void @init.$s(ptr %this${vals.joinToString("") { ", ${it.t.llvm} ${it.v}" }})")
            vals.forEach { releaseIfOwned(it) }
            afterCall()
        }
        fun store(n: String, v0: Val) {
            val idx = ci.index(n); val ft = ci.fields[idx].second
            val v = consume(coerce(v0, ft))
            emit("store ${ft.llvm} ${v.v}, ptr ${emitT("getelementptr ${ci.llType}, ptr %this, i32 0, i32 ${idx + 2}")}${tbField(ft.llvm)}")
        }
        for (f in c.fields) if (f.init == null) store(f.name, genArg(NameE(f.name).also { it.line = c.line }))
        for (x in c.inits) {
            if (x is FieldD) store(x.name, argFor(x.init!!, ci.fields[ci.index(x.name)].second, true))
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
        f.append("  call void @init.${ci.name}(ptr %this${if (ps.isEmpty()) "" else ", " + ps.joinToString(", ")})\n")
        if (excMode) f.append("  %ex = load ptr, ptr @rt_exc\n  %bad = icmp ne ptr %ex, null\n  br i1 %bad, label %drop, label %ok, !prof !1\n" +
            "drop:\n  call void ${releaseFn(Ty.Cls(ci.name))}(ptr %this)\n  ret ptr null\nok:\n")
        f.append("  ret ptr %this\n}\n\n")
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
        vals.forEachIndexed { i, v -> emit("store ${v.t.llvm} ${v.v}, ptr ${emitT("getelementptr ${ci.llType}, ptr $p, i32 0, i32 ${i + 2}")}${tbField(v.t.llvm)}") }
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
    /** Label of each entry in [loops]; [pendingLabel] is taken by the next loop pushed. */
    private val loopLabels = ArrayList<String?>()
    private var pendingLabel: String? = null
    private fun pushLoop(x: Triple<String, String, Int>) { loops.add(x); loopLabels.add(pendingLabel); pendingLabel = null }
    private fun popLoop() { loops.removeAt(loops.size - 1); loopLabels.removeAt(loopLabels.size - 1) }
    private fun releaseAll() { for (s in scopes.reversed()) releaseScope(s) }

    private fun emitReturn(v0: Val) {
        if (retTy == Ty.Unit) { releaseIfOwned(v0); runFinally(0); if (term) return; releaseAll(); emit("ret void"); term = true; return }
        var v = coerce(v0, retTy)
        if (v.t == Ty.Nothing) return
        if (v.t.isRef) v = consume(v)
        runFinally(0)
        if (term) return
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
            // `if (x !is T) return` narrows x for the rest of the block.
            val ifs = (s as? ExprS)?.e as? IfE
            if (!term && ifs != null && ifs.el == null && ifs.th.stmts.lastOrNull().let { it is RetS || it is JumpS || (it is ExprS && ((it.e as? CallE)?.callee as? NameE)?.n == "\$throw") })
                applyCasts(smartCasts(ifs.cond, false))
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
                val v0 = if (s.init is LambdaE && s.type != null) argFor(s.init, resolve(s.type)) else genArg(s.init)
                if (v0.t == Ty.Nothing) return
                val t = s.type?.let { resolve(it) } ?: v0.t
                if (t == Ty.Unit) fail("cannot store Unit in '${s.name}'")
                var v = coerce(v0, t)
                if (t.isRef) v = consume(v)
                val l = declare(s.name, t, s.isVar, true)
                emit("store ${t.llvm} ${v.v}, ptr ${l.slot}")
            }
            is AssignS -> {
                // `list += x` on a mutable list calls add (or addAll for a list): plusAssign.
                if ((s.op == "+=" || s.op == "-=") && s.target is NameE) {
                    val l = lookup(s.target.n)
                    if (l != null && l.t is Ty.ListTy) {
                        val savedFuncs = funcs.length; val savedBody = body.length; val savedAllocas = allocas.length; val savedCur = cur; val savedTerm = term
                        val vt = try { genExpr(s.value).t } catch (e: CompileError) { null } finally { funcs.setLength(savedFuncs); body.setLength(savedBody); allocas.setLength(savedAllocas); cur = savedCur; term = savedTerm }
                        val am = if (s.op == "+=") "add" else "remove"
                        if (vt is Ty.ListTy) {
                            val x = "\$pa${tmpN++}"
                            val add = ExprS(CallE(MemberE(s.target, am, false), emptyList(), listOf(NameE(x).also { it.line = s.line }), null).also { it.line = s.line }).also { it.line = s.line }
                            genStmt(ForS(x, s.value, Block(listOf(add))).also { it.line = s.line })
                        } else releaseIfOwned(genExpr(CallE(MemberE(s.target, am, false), emptyList(), listOf(s.value), null).also { it.line = s.line }))
                        return
                    }
                }
                val tg = s.target
                fun pure(x: E) = (x is NameE && lookup(x.n) != null) || x is IntL || (x is NameE && x.n == "this")
                if (s.op != "=" && tg is IndexE && !(pure(tg.recv) && pure(tg.idx))) {
                    // `a()[i()] += v`: receiver and index are evaluated once.
                    withTemps(listOf(genExpr(tg.recv), genExpr(tg.idx))) { (r, i) ->
                        val t2 = IndexE(r, i).also { it.line = s.line }
                        assign(t2, BinE(s.op.dropLast(1), t2, s.value).also { it.line = s.line }); Val("", Ty.Unit)
                    }
                    return
                }
                if (s.op != "=" && tg is MemberE && !pure(tg.recv) && !tg.safe) {
                    // Compound assignment evaluates the receiver once.
                    withTemps(listOf(genExpr(tg.recv))) { (x) ->
                        val t2 = MemberE(x, tg.n, false).also { it.line = s.line }
                        assign(t2, BinE(s.op.dropLast(1), t2, s.value).also { it.line = s.line }); Val("", Ty.Unit)
                    }
                    return
                }
                val value = if (s.op == "=") s.value else BinE(s.op.dropLast(1), s.target, s.value).also { it.line = s.line }
                assign(s.target, value)
            }
            is IncS -> releaseIfOwned(genExpr(PostIncE(s.target, s.op).also { it.line = s.line }))
            is WhileS -> if (s.isDo) {
                pendingLabel = s.label ?: pendingLabel
                val lc = label("do.cond"); val lb = label("do.body"); val le = label("do.end")
                startBlock(lb)
                pushLoop(Triple(lc, le, scopes.size))
                genBlockBody(s.body, false)
                startBlock(lc)
                // A do-while condition is inside the loop: `break` / `continue` in it target this loop (not so for `while`).
                val c = coerce(genExpr(s.cond), Ty.I1)
                popLoop()
                condBr(c.v, lb, le)
                startBlock(le)
            } else {
                val lc = label("while.cond"); val lb = label("while.body"); val le = label("while.end")
                val wl = s.label
                startBlock(lc)
                val c = coerce(genExpr(s.cond), Ty.I1)
                condBr(c.v, lb, le)
                startBlock(lb)
                pendingLabel = wl ?: pendingLabel
                pushLoop(Triple(lc, le, scopes.size))
                genBlockBody(s.body, false)
                popLoop()
                if (!term) br(lc)
                startBlock(le)
            }
            is ForS -> { pendingLabel = s.label; genFor(s); pendingLabel = null }
            is RetS -> {
                if (s.e == null) { if (retTy != Ty.Unit) fail("missing return value"); runFinally(0); if (!term) { releaseAll(); emit("ret void"); term = true } }
                else emitReturn(argFor(s.e, retTy, true))
            }
            is ExprS -> if (s.e is CallE && scopeFn(s.e, true) != null) genBlockBody(scopeFn(s.e, true)!!, false).let { }
            else if (s.e is TryE) { if (s.e.line != 0) line = s.e.line; genTry(s.e, false) } else releaseIfOwned(genExpr(s.e))
            is LocalDeclS -> fail("local declarations are only supported in function bodies")
            is JumpS -> {
                val li = if (s.label == null) loops.size - 1 else loopLabels.lastIndexOf(s.label).also { if (it < 0) fail("unknown loop label '${s.label}'") }
                val (lc, le, depth) = loops.getOrNull(li) ?: fail("'${if (s.isBreak) "break" else "continue"}' outside a loop")
                runFinally(depth)
                if (term) return
                for (k in scopes.size - 1 downTo depth) releaseScope(scopes[k])
                br(if (s.isBreak) le else lc)
            }
        }
    }

    /** Runs [f] with [target], its receiver evaluated once into a temporary when that has side effects. */
    private fun onceRecv(target: E, f: (E) -> Val): Val =
        if (target is MemberE && target.recv !is NameE && !target.safe)
            withTemps(listOf(genExpr(target.recv))) { (x) -> f(MemberE(x, target.n, false).also { it.line = target.line }) }
        else f(target)

    private fun assign(target: E, value: E) {
        when (target) {
            is NameE -> {
                val l = lookup(target.n)
                if (l == null && owner != null && (owner!!.index(target.n) >= 0 || "\$set\$${target.n}" in owner!!.methods)) return assign(MemberE(NameE("this"), target.n, false), value)
                if (l == null && "\$tset\$${target.n}" in funDecls) { releaseIfOwned(genNamedCall("\$tset\$${target.n}", CallE(NameE("\$tset\$${target.n}"), emptyList(), listOf(value), null).also { it.line = line })); return }
                if (l == null) fail("unknown variable '${target.n}'")
                if (!l.isVar) fail("val '${target.n}' cannot be reassigned")
                var v = coerce(argFor(value, l.t, true), l.t)
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
                val sm = "\$set\$${target.n}"
                if (ci.index(target.n) < 0 && sm in ci.methods) {
                    releaseIfOwned(callMethod(r, ci, sm, CallE(NameE(sm), emptyList(), listOf(value), null).also { it.line = line })); releaseIfOwned(r); return
                }
                val idx = ci.index(target.n).takeIf { it >= 0 } ?: fail("no field ${target.n} in ${ct.name}")
                val ft = ci.fields[idx].second
                var v = coerce(argFor(value, ft, true), ft)
                val ptr = emitT("getelementptr ${ci.llType}, ptr ${r.v}, i32 0, i32 ${idx + 2}")
                if (ft.isRef) {
                    v = consume(v)
                    val old = emitT("load ptr, ptr $ptr${tbField("ptr")}")
                    emit("store ptr ${v.v}, ptr $ptr${tbField("ptr")}")
                    release(Val(old, ft))
                } else emit("store ${ft.llvm} ${v.v}, ptr $ptr${tbField(ft.llvm)}")
                releaseIfOwned(r)
            }
            is IndexE -> {
                val r = genExpr(target.recv)
                if (r.t is Ty.Cls || r.t is Ty.Iface) {
                    releaseIfOwned(method(r, "set", CallE(MemberE(target.recv, "set", false), emptyList(), listOf(target.idx, value), null)))
                    return
                }
                val i0 = genExpr(target.idx)
                val i = if (r.t is Ty.MapTy) i0 else coerce(i0, Ty.I64)
                when (val rt = r.t) {
                    is Ty.Arr -> {
                        val q = elemPtr(r, i)
                        val v = coerce(genExpr(value), rt.elem)
                        emit("store ${rt.elem.llvm} ${v.v}, ptr $q${tbElem(rt.elem.llvm)}")
                    }
                    is Ty.MapTy -> {
                        val v = coerce(genExpr(value), rt.v)
                        emit("call void @rt_map_put(ptr ${r.v}, i64 ${mapKey(i0, rt)}, i64 ${toSlot(v)})")
                    }
                    is Ty.ListTy -> {
                        val v = consume(coerce(genArg(value), rt.elem))
                        listIdx(r, i)
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
            val uns = lo0.t.isUnsigned || hi0.t.isUnsigned
            val wide = lo0.t == Ty.I64 || hi0.t == Ty.I64 || lo0.t == Ty.U64 || hi0.t == Ty.U64
            val t = if (lo0.t == Ty.Char && hi0.t == Ty.Char) Ty.Char else if (uns) (if (wide) Ty.U64 else Ty.U32) else if (wide) Ty.I64 else Ty.I32
            val at = if (t == Ty.Char) Ty.I32 else t
            val lo = if (t == Ty.Char) lo0 else coerce(lo0, t); val hi = if (t == Ty.Char) hi0 else coerce(hi0, t)
            val stepV = if (it !== rng) {
                val sv = coerce(genExpr((it as BinE).r), at)
                val bad = label("step.bad"); val ok = label("step.ok")
                condBr(emitT("icmp sle ${at.llvm} ${sv.v}, 0"), bad, ok)
                startBlock(bad)
                if (excMode) throwNew("IllegalArgumentException", sbMsg("Step must be positive, was: ", coerce(sv, Ty.I64), "."))
                else { emit("call void @rt_bad_step(i64 ${coerce(sv, Ty.I64).v})"); emit("unreachable"); term = true }
                startBlock(ok)
                sv.v
            } else "1"
            val idx = declare(s.name, t, false, false)
            emit("store ${t.llvm} ${lo.v}, ptr ${idx.slot}")
            val cmp = (if (uns) "u" else "s") + when (rng.op) { ".." -> "le"; "until" -> "lt"; else -> "ge" }
            val topTested = stepV == "1" && rng.op == "until"
            if (topTested) {
                // `lo until hi`: classic top-tested counted loop (what LLVM's loop passes and vectorizer expect).
                startBlock(lc)
                condBr(emitT("icmp $cmp ${t.llvm} ${emitT("load ${t.llvm}, ptr ${idx.slot}")}, ${hi.v}"), lb, le)
            } else condBr(emitT("icmp $cmp ${t.llvm} ${lo.v}, ${hi.v}"), lb, le)
            // Inclusive last bound (lo < hi holds for `until` here, so hi - 1 cannot overflow).
            startBlock(lb)
            pushLoop(Triple(ls, le, scopes.size))
            genBlockBody(s.body, false)
            popLoop()
            startBlock(ls)
            if (stepV == "1") {
                // Unit step: canonical counted loop. The index stays within [lo, hi] before the step, so the
                // increment cannot overflow (nsw), which lets LLVM widen the index and vectorize.
                val i2 = emitT("load ${t.llvm}, ptr ${idx.slot}")
                val inc = label("for.inc")
                if (topTested) {
                    emit("store ${t.llvm} ${emitT("add ${if (uns) "nuw" else "nsw"} ${t.llvm} $i2, 1")}, ptr ${idx.slot}")
                    br(lc)
                    startBlock(le)
                } else {
                    condBr(emitT("icmp eq ${t.llvm} $i2, ${hi.v}"), le, inc)
                    startBlock(inc); emit("store ${t.llvm} ${emitT("${if (down) "sub" else "add"} ${if (uns) "nuw" else "nsw"} ${t.llvm} $i2, 1")}, ptr ${idx.slot}")
                    br(lb)
                    startBlock(le)
                }
            } else {
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
            }
        } else {
            val coll = genExpr(it)
            if (progKind(coll.t) != null) { iterProg(coll, s); scopes.removeAt(scopes.size - 1); return }
            val elemT = when (val ct = coll.t) { is Ty.Arr -> ct.elem; Ty.Str -> Ty.Char; is Ty.ListTy -> ct.elem; else -> fail("cannot iterate over ${coll.t}") }
            val arr = coll.t as? Ty.Arr
            val idx = "%l.idx.${tmpN++}"
            allocas.append("  $idx = alloca i64\n")
            emit("store i64 0, ptr $idx")
            val v = declare(s.name, elemT, false, false)
            val data = if (arr != null) emitT("getelementptr i8, ptr ${coll.v}, i64 8") else ""
            val len = if (arr != null) emitT("load i64, ptr ${coll.v}${tbLen()}") else if (coll.t == Ty.Str) emitT("call i64 @strlen(ptr ${coll.v})") else ""
            startBlock(lc)
            val i = emitT("load i64, ptr $idx")
            val n = if (len != "") len else emitT("call i64 @rt_list_size(ptr ${coll.v})")
            condBr(emitT("icmp slt i64 $i, $n"), lb, le)
            startBlock(lb)
            val e = if (arr != null) emitT("load ${elemT.llvm}, ptr ${emitT("getelementptr ${elemT.llvm}, ptr $data, i64 $i")}${tbElem(elemT.llvm)}")
            else if (coll.t == Ty.Str) emitT("zext i8 ${emitT("load i8, ptr ${emitT("getelementptr i8, ptr ${coll.v}, i64 $i")}")} to i32")
            else fromSlot(emitT("call i64 @rt_list_get(ptr ${coll.v}, i64 $i)"), elemT)
            emit("store ${elemT.llvm} $e, ptr ${v.slot}")
            pushLoop(Triple(ls, le, scopes.size))
            genBlockBody(s.body, false)
            popLoop()
            startBlock(ls)
            emit("store i64 ${emitT("add i64 ${emitT("load i64, ptr $idx")}, 1")}, ptr $idx")
            br(lc)
            startBlock(le)
            releaseIfOwned(coll)
        }
        scopes.removeAt(scopes.size - 1)
    }

    /**
     * `for (x in p)` over a progression value: `var n = p.first; var go = !p.isEmpty()
     * while (go) { val x = n; if (x == p.last) go = false else n = x + p.step; body }` (never steps past `last`).
     */
    private fun iterProg(v: Val, s: ForS) {
        val ln = s.line
        fun <T : E> T.l(): T = also { x -> x.line = ln }
        fun <T : S> T.l(): T = also { x -> x.line = ln }
        withTemps(listOf(v)) { (p) ->
            val n = "\$n${tmpN++}"; val go = "\$go${tmpN++}"
            val mem = { f: String -> MemberE(p, f, false).l() }
            genStmt(VarS(n, true, null, mem("first")).l())
            genStmt(VarS(go, true, null, UnE("!", CallE(mem("isEmpty"), emptyList(), emptyList(), null).l()).l()).l())
            val adv = ExprS(IfE(BinE("==", NameE(s.name).l(), mem("last")).l(),
                Block(listOf(AssignS(NameE(go).l(), "=", BoolL(false).l()).l())),
                Block(listOf(AssignS(NameE(n).l(), "=", BinE("+", NameE(s.name).l(), mem("step")).l()).l()))).l()).l()
            genStmt(WhileS(NameE(go).l(), Block(listOf(VarS(s.name, false, null, NameE(n).l()).l(), adv) + s.body.stmts)).l())
            Val("", Ty.Unit)
        }
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
                val pre = e.op.startsWith("pre")
                val op = e.op.removePrefix("pre")
                val mname = if (op == "++") "inc" else "dec"
                onceRecv(e.target) { tg ->
                    val old = genExpr(tg)
                    // `operator fun inc()/dec()` (member, or an extension on the operand type).
                    val custom = (old.t as? Ty.Cls)?.let { mname in classes.getValue(it.name).methods } == true ||
                        extFuns[mname]?.any { extMatches(old.t, it.params[0].type, it.typeParams) } == true
                    if (!custom && old.t.llvm == "ptr" && old.t !is Ty.NBox) fail("$op on ${old.t}")
                    // The getter runs once for the old value; prefix forms read the property again for the result.
                    withTemps(listOf(old)) { (x) ->
                        val savedIv = incViaExt; incViaExt = custom
                        try { assign(tg, (if (custom) CallE(MemberE(x, mname, false).also { it.line = e.line }, emptyList(), emptyList(), null)
                            else BinE(if (op == "++") "+" else "-", x, IntL(1, false))).also { it.line = e.line }) } finally { incViaExt = savedIv }
                        genExpr(if (pre) tg else x)
                    }
                }
            }
            is DblL -> Val("0x" + e.v.toRawBits().toULong().toString(16).uppercase().padStart(16, '0'), if (e.f32) Ty.F32 else Ty.F64)
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
                    owner != null && (owner!!.index(e.n) >= 0 || "\$prop\$${e.n}" in owner!!.methods) -> property(Val("%this", Ty.Cls(owner!!.name)), e.n)
                    e.n in CONSTS -> Val("${CONSTS.getValue(e.n)}", Ty.I32)
                    // The `Unit` object as a value (`return Unit`, `else -> Unit`).
                    e.n == "Unit" && "Unit" !in classes -> Val("", Ty.Unit)
                    "\$get\$${e.n}" in sigs -> genNamedCall("\$get\$${e.n}", CallE(NameE("\$get\$${e.n}"), emptyList(), emptyList(), null).also { it.line = e.line })
                    // Implicit receiver of a top-level extension function.
                    lookup("this") != null -> property(genExpr(NameE("this").also { it.line = e.line }), e.n)
                    else -> fail("unknown name '${e.n}'")
                }
            }
            is MemberE -> genMember(e)
            is LambdaE -> genLambda(e, null, null)
            is NamedE -> fail("named arguments are not supported")
            is IsE -> { val v = genExpr(e.e); val r = isCheck(v, e.type); releaseIfOwned(v); Val(if (e.neg) emitT("xor i1 $r, true") else r, Ty.I1) }
            is AsE -> genAs(e)
            is CallE -> genCall(e)
            is IndexE -> {
                val r = genExpr(e.recv)
                if (r.t is Ty.Cls || r.t is Ty.Iface) return method(r, "get", opCall("get", e.idx))
                val i0 = genExpr(e.idx)
                val i = if (r.t is Ty.MapTy) i0 else coerce(i0, Ty.I64)
                val res = when (val rt = r.t) {
                    is Ty.Arr -> Val(emitT("load ${rt.elem.llvm}, ptr ${elemPtr(r, i)}${tbElem(rt.elem.llvm)}"), rt.elem)
                    is Ty.MapTy -> Val(fromSlot(emitT("call i64 @rt_map_get(ptr ${r.v}, i64 ${mapKey(i0, rt)})"), rt.v), rt.v)
                    Ty.Str -> Val(emitT("zext i8 ${emitT("load i8, ptr ${emitT("getelementptr i8, ptr ${r.v}, i64 ${i.v}")}")} to i32"), Ty.Char)
                    is Ty.ListTy -> { listIdx(r, i); Val(fromSlot(emitT("call i64 @rt_list_get(ptr ${r.v}, i64 ${i.v})"), rt.elem), rt.elem) }
                    else -> fail("cannot index ${r.t}")
                }
                if (r.owned) { val o = consume(res); releaseIfOwned(r); o } else res
            }
            is BinE -> genBin(e)
            is UnE -> {
                if (e.op.startsWith("::")) return genRef(e, if (e.e is NullL) null else e.e, e.op.removePrefix("::"))
                val v = genExpr(e.e)
                if (e.op == "!!") return genNonNull(v)
                if (v.t is Ty.Cls || v.t is Ty.Iface) return method(v, when (e.op) { "-" -> "unaryMinus"; "+" -> "unaryPlus"; else -> "not" }, opCall("", null))
                when (e.op) {
                    "!" -> Val(emitT("xor i1 ${coerce(v, Ty.I1).v}, true"), Ty.I1)
                    "+" -> if (v.t.isNum) coerce(v, if (v.t == Ty.I8) Ty.I32 else v.t) else method(v, "unaryPlus", opCall("", null))
                    "-" -> when {
                        v.t.isFp -> Val(emitT("fneg ${v.t.llvm} ${v.v}"), v.t)
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
                scopes.add(Scope()); applyCasts(smartCasts(e.cond, true))
                val a = try { genBlockBody(e.th, e.el != null) } finally { scopes.removeAt(scopes.size - 1) }
                if (!term) { res.add(a to cur); br(le) }
                startBlock(lf)
                if (inh.isNotEmpty()) inheritNext = inh
                scopes.add(Scope()); applyCasts(smartCasts(e.cond, false))
                val b = try { if (e.el != null) genBlockBody(e.el, true) else Val("", Ty.Unit) } finally { scopes.removeAt(scopes.size - 1) }
                if (!term) { res.add(b to cur); br(le) }
                startBlock(le)
                merge(res, e.el != null)
            }
            is WhenE -> genWhen(e)
            is TryE -> genTry(e, true)
        }
    }

    // ---------------------------------------------------------------- exceptions
    //
    // With the prelude's exception classes present (a program using `try`), exceptions are objects: `throw` stores
    // the exception in @rt_exc and unwinds; every call that may throw is followed by a check of @rt_exc. Unwinding
    // releases the scopes' references up to the innermost `try` (then dispatches to its catch clauses) or the whole
    // function (then returns a dummy value to the caller, which unwinds in turn). Uncaught exceptions are reported
    // by main. Without `try`, `throw` terminates the program directly (no checks).

    private val excMode by lazy { "Throwable" in classDecls }
    /** Innermost-last: (catch dispatch label, scope depth at `try` entry). */
    private val tryStack = ArrayList<Pair<String, Int>>()
    /** Innermost-last: `finally` blocks of the enclosing `try`s with the scope depth at their `try`. */
    private val finStack = ArrayList<Pair<Block, Int>>()

    /** Runs the `finally` blocks of the `try`s entered at scope depth >= [depth] (a jump or return leaving them). */
    private fun runFinally(depth: Int) {
        val saved = ArrayList(finStack); val savedTry = ArrayList(tryStack)
        try {
            while (finStack.isNotEmpty() && finStack.last().second >= depth && !term) {
                val (f, d) = finStack.removeAt(finStack.size - 1)
                // An exception thrown by the finally block leaves its try (no catch clause or finally of it applies).
                while (tryStack.isNotEmpty() && tryStack.last().second >= d) tryStack.removeAt(tryStack.size - 1)
                genBlockBody(f, false)
            }
        } finally { finStack.clear(); finStack.addAll(saved); tryStack.clear(); tryStack.addAll(savedTry) }
    }

    /** Exception constructors may omit the message (passed as null). */
    private fun excArgs(cls: String, args: List<E>): List<E> = if (cls in PRELUDE_EXCEPTIONS && args.isEmpty()) listOf(NullL()) else args

    private fun emitDefaultRet() {
        emit(when (retTy.llvm) { "void" -> "ret void"; "ptr" -> "ret ptr null"; "double" -> "ret double 0.0"; "float" -> "ret float 0.0"; "i1" -> "ret i1 false"; else -> "ret ${retTy.llvm} 0" })
        term = true
    }

    /** Leaves the current code position with the pending exception: to the innermost catch dispatch or the caller. */
    private fun unwind() {
        val t = tryStack.lastOrNull()
        if (t != null) {
            for (k in scopes.size - 1 downTo t.second) releaseScope(scopes[k])
            br(t.first)
        } else { releaseAll(); emitDefaultRet() }
    }

    /** After a call that may throw: unwinds if an exception is pending. */
    private fun afterCall() {
        if (!excMode || term) return
        val e = emitT("load ptr, ptr @rt_exc")
        val lu = label("exc.unwind"); val lc = label("exc.cont")
        emit("br i1 ${emitT("icmp ne ptr $e, null")}, label %$lu, label %$lc, !prof !1"); term = true
        startBlock(lu)
        unwind()
        startBlock(lc)
    }

    /** Classes that are [cls] or derive from it. */
    private fun subclassesOf(cls: String): List<String> = classes.values.filter { it.name == cls || cls in it.ancestors }.map { it.name }.sorted()

    /** Dynamic type test of a non-null object pointer against class [cls] (compares vtables). */
    private fun isInstance(p: String, cls: String): String {
        val subs = subclassesOf(cls)
        if (subs.isEmpty()) return "false"
        val vt = emitT("load ptr, ptr ${emitT("getelementptr i8, ptr $p, i64 8")}")
        var r: String? = null
        for (x in subs) { val c = emitT("icmp eq ptr $vt, @vt.$x"); r = if (r == null) c else emitT("or i1 $r, $c") }
        return r!!
    }

    private fun throwValue(v0: Val) {
        val t = v0.t as? Ty.Cls
        if (t == null || "Throwable" !in subclassesOf("Throwable").let { _ -> classes.getValue(t.name).ancestors + t.name }) fail("can only throw Throwable objects, found ${v0.t}")
        val v = consume(v0)
        emit("store ptr ${v.v}, ptr @rt_exc")
        unwind()
    }

    /** `throw Cls(msg)` for a computed message value. */
    private fun throwNew(cls: String, msg: Val) {
        withTemps(listOf(msg)) { (m) -> throwValue(genNamedCall(cls, CallE(NameE(cls), emptyList(), listOf(m), null))); Val("undef", Ty.Nothing) }
    }

    /** A string built from literal parts and values (for runtime error messages). */
    private fun sbMsg(vararg parts: Any): Val {
        val sb = emitT("call ptr @rt_sb_new()")
        for (x in parts) if (x is String) emit("call void @rt_sb_str(ptr $sb, ptr ${cstr(x)})") else appendVal("rt_sb_", "ptr $sb, ", x as Val)
        return Val(emitT("call ptr @rt_sb_done(ptr $sb)"), Ty.Str, true)
    }

    /** With catchable exceptions, list indexing checks bounds here (IndexOutOfBoundsException) instead of in the runtime. */
    private fun listIdx(r: Val, i: Val) {
        if (!excMode) return
        val n = emitT("call i64 @rt_list_size(ptr ${r.v})")
        val bad = label("lidx.bad"); val ok = label("lidx.ok")
        emit("br i1 ${emitT("icmp ult i64 ${i.v}, $n")}, label %$ok, label %$bad, !prof !0"); term = true
        startBlock(bad)
        throwNew("IndexOutOfBoundsException", sbMsg("Index ", Val(i.v, Ty.I64), " out of bounds for length ", Val(n, Ty.I64)))
        startBlock(ok)
    }

    private fun genTry(e: TryE, want: Boolean): Val {
        val handler = label("try.catch"); val le = label("try.end")
        val finLabel = if (e.fin != null) label("try.fin") else null
        val depth = scopes.size
        val res = ArrayList<Pair<Val, String>>()
        tryStack.add(handler to depth)
        e.fin?.let { finStack.add(it to depth) }
        val v = genBlockBody(e.body, want)
        tryStack.removeAt(tryStack.size - 1)
        if (e.fin != null) finStack.removeAt(finStack.size - 1)
        if (!term) {
            e.fin?.let { genBlockBody(it, false) }
            if (!term) { res.add(v to cur); br(le) }
        }
        startBlock(handler)
        for (c in e.catches) {
            val ct = resolve(c.type) as? Ty.Cls ?: fail("catch needs an exception class, found ${c.type}")
            if ("Throwable" !in classes.getValue(ct.name).ancestors + ct.name) fail("catch needs a Throwable subclass, found ${ct.name}")
            val exc = emitT("load ptr, ptr @rt_exc")
            val lm = label("catch.match"); val ln = label("catch.next")
            condBr(isInstance(exc, ct.name), lm, ln)
            startBlock(lm)
            emit("store ptr null, ptr @rt_exc")
            e.fin?.let { finStack.add(it to depth) }
            finLabel?.let { tryStack.add(it to depth) }
            scopes.add(Scope())
            val l = declare(c.name, ct, false, true)
            emit("store ptr $exc, ptr ${l.slot}")
            val cv = genBlockBody(c.body, want)
            if (!term) releaseScope(scopes.last())
            scopes.removeAt(scopes.size - 1)
            if (e.fin != null) { finStack.removeAt(finStack.size - 1); tryStack.removeAt(tryStack.size - 1) }
            if (!term) {
                e.fin?.let { genBlockBody(it, false) }
                if (!term) { res.add(cv to cur); br(le) }
            }
            startBlock(ln)
        }
        // No clause matched (or a catch clause threw): run `finally` with the exception set aside, then keep unwinding.
        e.fin?.let { f ->
            startBlock(finLabel!!)
            val exc = emitT("load ptr, ptr @rt_exc")
            emit("store ptr null, ptr @rt_exc")
            genBlockBody(f, false)
            if (!term) emit("store ptr $exc, ptr @rt_exc")
        }
        if (!term) unwind()
        startBlock(le)
        if (!want) return Val("", Ty.Unit)
        return merge(res, true)
    }

    /** @k.uncaught: reports a pending exception like the JVM ("Exception in thread "main" <class>: <message>") and exits. */
    private fun genUncaught() {
        val f = StringBuilder("define internal void @k.uncaught() {\nentry:\n  %e = load ptr, ptr @rt_exc\n  %z = icmp eq ptr %e, null\n  br i1 %z, label %none, label %some\n")
        f.append("none:\n  ret void\nsome:\n  %mp = getelementptr i8, ptr %e, i64 16\n  %m = load ptr, ptr %mp\n  %vt = getelementptr i8, ptr %e, i64 8\n  %v = load ptr, ptr %vt\n")
        val subs = subclassesOf("Throwable")
        subs.forEachIndexed { i, x ->
            val qn = EXCEPTIONS[x] ?: if (x in PRELUDE_EXCEPTIONS) "kotlin.$x" else x
            f.append("  %c$i = icmp eq ptr %v, @vt.$x\n  br i1 %c$i, label %is$i, label %not$i\nis$i:\n  call void @rt_throw(ptr ${cstr(qn)}, ptr %m)\n  unreachable\nnot$i:\n")
        }
        f.append("  call void @rt_throw(ptr ${cstr("kotlin.Throwable")}, ptr %m)\n  unreachable\n}\n\n")
        funcs.append(f)
    }

    /** Joins branch results with a phi when every live branch produced a value of the same type. */
    private fun merge(res: List<Pair<Val, String>>, exhaustive: Boolean): Val {
        if (res.isEmpty()) { emit("unreachable"); term = true; return Val("undef", Ty.Nothing) }
        // A `null` branch takes the type of the other (reference) branches.
        val typed = res.filter { it.first.v != "null" }.ifEmpty { res }
        var t = typed[0].first.t
        // `if (c) 5 else null`: a primitive and a null branch make an `Int?` (boxed after the join).
        if (exhaustive && res.size > 1 && res.any { it.first.v == "null" } && typed.all { it.first.t == t && it.first.v != "null" } && canBox(t)) {
            val isNull = emitT("phi i1 " + res.joinToString(", ") { "[ ${it.first.v == "null"}, %${it.second} ]" })
            val raw = emitT("phi ${t.llvm} " + res.joinToString(", ") { "[ ${if (it.first.v == "null") "undef" else it.first.v}, %${it.second} ]" })
            val from = cur; val lb = label("nbox.box"); val lj = label("nbox.join")
            condBr(isNull, lj, lb)
            startBlock(lb)
            val bx = coerce(Val(raw, t), Ty.NBox(t)); val bEnd = cur
            br(lj)
            startBlock(lj)
            return Val(emitT("phi ptr [ null, %$from ], [ ${bx.v}, %$bEnd ]"), Ty.NBox(t), true)
        }
        if (exhaustive && typed.any { it.first.t != t }) commonIface(typed.map { it.first.t })?.let { t = it }
        if (!exhaustive || t == Ty.Unit || res.any { it.first.t != t && !(it.first.v == "null" && t.llvm == "ptr") && !((t is Ty.Iface || t is Ty.Cls) && it.first.t is Ty.Cls) }) {
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
        scopes.add(Scope())
        if (subj != null && subj.t != Ty.Unit) { val l = declare("\$subj", subj.t, false, false); emit("store ${subj.t.llvm} ${subj.v}, ptr ${l.slot}") }
        try { return genWhenBody(e, subj) } finally { scopes.removeAt(scopes.size - 1) }
    }

    private fun isSubjCond(c: E) = (c is IsE && (c.e as? NameE)?.n == "\$subj") || (c is BinE && (c.op == "in" || c.op == "!in") && (c.l as? NameE)?.n == "\$subj")

    private fun genWhenBody(e: WhenE, subj: Val?): Val {
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
                // After `!is Float ->` / `!is Double ->`, an `Any` subject is smart-cast: compare a literal with IEEE `==`.
                val fpCast = if (subj != null && isAny(subj.t) && ce is DblL) e.branches.take(bi).any { b -> b.first?.singleOrNull()?.let { it is IsE && it.neg && isSubjCond(it) && it.type.name == (if (ce.f32) "Float" else "Double") && !it.type.nullable } == true } else false
                val x = if (fpCast) compare("==", unbox(Val(subj!!.v, subj.t, false), if ((ce as DblL).f32) Ty.F32 else Ty.F64), genExpr(ce)).v
                else if (subj != null && !isSubjCond(ce)) compare("==", Val(subj.v, subj.t, false), genExpr(ce)).v else coerce(genExpr(ce), Ty.I1).v
                c = if (c == null) x else emitT("or i1 $c, $x")
            }
            condBr(c!!, lt, ln)
            startBlock(lt)
            val casts = conds.singleOrNull()?.let { ce ->
                if (subj != null && ce is IsE && isSubjCond(ce)) listOfNotNull((e.subj as? NameE)?.let { IsE(it, ce.type, ce.neg) }, IsE(NameE("\$subj"), ce.type, ce.neg)).flatMap { smartCasts(it, true) }
                else if (subj == null) smartCasts(ce, true) else emptyList()
            } ?: emptyList()
            scopes.add(Scope()); applyCasts(casts)
            val v = try { genBlockBody(blk, true) } finally { scopes.removeAt(scopes.size - 1) }
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
    private fun appendVal(prefix: String, lead: String, v: Val, checked: Boolean = false) {
        if (!checked && v.v != "null" && (v.t is Ty.Cls || v.t is Ty.ListTy || v.t is Ty.NBox || (v.t is Ty.Iface && !isAny(v.t)))) {
            // A nullable object prints as "null".
            val ln = label("ts.null"); val lv = label("ts.val"); val le = label("ts.end")
            condBr(emitT("icmp eq ptr ${v.v}, null"), ln, lv)
            startBlock(ln); emit("call void @${prefix}str($lead ptr ${cstr("null")})"); br(le)
            startBlock(lv); appendVal(prefix, lead, v, true); if (!term) br(le)
            startBlock(le)
            return
        }
        if (v.t is Ty.NBox) return appendVal(prefix, lead, unbox(Val(v.v, v.t, false), v.t.inner))
        when {
            v.t == Ty.Str || v.t == Ty.RawPtr -> emit("call void @${prefix}str($lead ptr ${v.v})")
            v.t == Ty.Char -> emit("call void @${prefix}char($lead i32 ${v.v})")
            v.t.isUnsigned -> emit("call void @${prefix}u64($lead i64 ${coerce(v, Ty.U64).v})")
            v.t.isInt -> emit("call void @${prefix}i64($lead i64 ${coerce(v, Ty.I64).v})")
            v.t == Ty.F64 -> emit("call void @${prefix}f64($lead double ${v.v})")
            v.t == Ty.F32 -> emit("call void @${prefix}f32($lead float ${v.v})")
            v.t == Ty.I1 -> emit("call void @${prefix}bool($lead i1 ${v.v})")
            isAny(v.t) -> appendVal(prefix, lead, anyCall("anyToString__", listOf(Val(v.v, v.t, false))))
            v.t is Ty.Cls && classes.getValue(v.t.name).methods["toString"]?.params?.isEmpty() == true -> {
                val sv = callMethod(Val(v.v, v.t), classes.getValue(v.t.name), "toString", CallE(NameE("toString"), emptyList(), emptyList(), null))
                appendVal(prefix, lead, coerce(sv, Ty.Str))
            }
            v.t is Ty.ListTy -> {
                // "[a, b, c]"
                emit("call void @${prefix}str($lead ptr ${cstr("[")})")
                val idx = "%l.ts.${tmpN++}"
                allocas.append("  $idx = alloca i64\n")
                emit("store i64 0, ptr $idx")
                val lc = label("lts.cond"); val lb = label("lts.body"); val ls = label("lts.sep"); val lv = label("lts.val"); val le = label("lts.end")
                startBlock(lc)
                val i = emitT("load i64, ptr $idx")
                condBr(emitT("icmp slt i64 $i, ${emitT("call i64 @rt_list_size(ptr ${v.v})")}"), lb, le)
                startBlock(lb)
                condBr(emitT("icmp eq i64 $i, 0"), lv, ls)
                startBlock(ls)
                emit("call void @${prefix}str($lead ptr ${cstr(", ")})")
                br(lv)
                startBlock(lv)
                val et = v.t.elem
                appendVal(prefix, lead, Val(fromSlot(emitT("call i64 @rt_list_get(ptr ${v.v}, i64 $i)"), et), et))
                emit("store i64 ${emitT("add i64 $i, 1")}, ptr $idx")
                br(lc)
                startBlock(le)
                emit("call void @${prefix}str($lead ptr ${cstr("]")})")
            }
            v.t is Ty.Cls && classDecls[v.t.name]?.enumEntries != null -> {
                val ci = classes.getValue(v.t.name)
                val np = emitT("load ptr, ptr ${emitT("getelementptr ${ci.llType}, ptr ${v.v}, i32 0, i32 2")}")
                emit("call void @${prefix}str($lead ptr $np)")
            }
            v.t is Ty.Cls && classDecls[v.t.name]?.isValue == true -> {
                val ci = classes.getValue(v.t.name); val (fname, ft) = ci.fields[0]
                emit("call void @${prefix}str($lead ptr ${cstr(v.t.name.substringBefore('$') + "(" + fname + "=")})")
                appendVal(prefix, lead, Val(emitT("load ${ft.llvm}, ptr ${emitT("getelementptr ${ci.llType}, ptr ${v.v}, i32 0, i32 2")}"), ft))
                emit("call void @${prefix}str($lead ptr ${cstr(")")})")
            }
            v.t is Ty.Cls -> emit("call void @${prefix}str($lead ptr ${cstr(v.t.name + "@obj")})")
            else -> fail("cannot convert ${v.t} to string")
        }
        releaseIfOwned(v)
    }

    private fun genMember(e: MemberE): Val {
        flatten(e)?.let { LIMITS[it]?.let { (v, t) -> return Val(v, t) } }
        if (e.safe) return safe(e.recv) { r -> property(r, e.n) }
        if (e.recv is NameE && e.recv.n == "super") {
            // `super.x`: the superclass getter if it has one, else the (inherited) field.
            val pm = "\$prop\$${e.n}"
            if (superOf().index(e.n) < 0 && pm in superOf().methods) return superCall(pm, CallE(NameE(pm), emptyList(), emptyList(), null).also { it.line = line })
            return property(Val("%this", Ty.Cls(owner!!.name)), e.n)
        }
        return property(genExpr(e.recv), e.n)
    }

    private val LIMITS = mapOf("Char.MIN_VALUE" to ("0" to Ty.Char), "Char.MAX_VALUE" to ("65535" to Ty.Char),
        "Long.MAX_VALUE" to ("9223372036854775807" to Ty.I64), "Long.MIN_VALUE" to ("-9223372036854775808" to Ty.I64),
        "Int.MAX_VALUE" to ("2147483647" to Ty.I32), "Int.MIN_VALUE" to ("-2147483648" to Ty.I32),
        "Short.MAX_VALUE" to ("32767" to Ty.I32), "Short.MIN_VALUE" to ("-32768" to Ty.I32),
        "UByte.MAX_VALUE" to ("255" to Ty.U32), "UByte.MIN_VALUE" to ("0" to Ty.U32), "UShort.MAX_VALUE" to ("65535" to Ty.U32), "UShort.MIN_VALUE" to ("0" to Ty.U32),
        "UInt.MAX_VALUE" to ("4294967295" to Ty.U32), "UInt.MIN_VALUE" to ("0" to Ty.U32), "ULong.MAX_VALUE" to ("-1" to Ty.U64), "ULong.MIN_VALUE" to ("0" to Ty.U64),
        "Byte.MAX_VALUE" to ("127" to Ty.I8), "Byte.MIN_VALUE" to ("-128" to Ty.I8),
        // Double constants as LLVM hex bit patterns.
        "Double.NaN" to ("0x7FF8000000000000" to Ty.F64), "Double.POSITIVE_INFINITY" to ("0x7FF0000000000000" to Ty.F64),
        "Double.NEGATIVE_INFINITY" to ("0xFFF0000000000000" to Ty.F64), "Double.MAX_VALUE" to ("0x7FEFFFFFFFFFFFFF" to Ty.F64),
        "Double.MIN_VALUE" to ("0x0000000000000001" to Ty.F64),
        // Float constants: LLVM wants the double bit pattern of the (exactly representable) float value.
        "Float.NaN" to ("0x7FF8000000000000" to Ty.F32), "Float.POSITIVE_INFINITY" to ("0x7FF0000000000000" to Ty.F32),
        "Float.NEGATIVE_INFINITY" to ("0xFFF0000000000000" to Ty.F32), "Float.MAX_VALUE" to ("0x47EFFFFFE0000000" to Ty.F32),
        "Float.MIN_VALUE" to ("0x36A0000000000000" to Ty.F32))

    private val lateinitFields by lazy { prog.classes.flatMap { c -> c.fields.filter { it.lateinit }.map { "${c.name}.${it.name}" } }.toSet() }

    private fun property(r: Val, n: String): Val {
        // Property getters (`val x: T get() = ...`) of classes and extension properties: `$prop$x` functions.
        val pm = "\$prop\$$n"
        (r.t as? Ty.Cls)?.let { t -> val ci = classes.getValue(t.name)
            if (ci.index(n) < 0 && pm in ci.methods) { val v = callMethod(r, ci, pm, CallE(NameE(pm), emptyList(), emptyList(), null)); releaseIfOwned(r); return v }
        }
        (r.t as? Ty.Iface)?.let { t -> ifaces[t.name]?.takeIf { pm in it.methods }?.let { ii -> val v = callIface(r, ii, pm, CallE(NameE(pm), emptyList(), emptyList(), null).also { it.line = line }); releaseIfOwned(r); return v } }
        if (!hasMember(r.t, n)) extFuns[pm]?.firstOrNull { extMatches(r.t, it.params[0].type, it.typeParams) }?.let { f ->
            return withTemps(listOf(r)) { (x) -> genNamedCall(f.name, CallE(NameE(f.name), emptyList(), listOf(x), null).also { it.line = line }) }
        }
        val res = when (val t = r.t) {
            is Ty.Cls -> {
                val ci = classes.getValue(t.name)
                val idx = ci.index(n).takeIf { it >= 0 } ?: fail("no field $n in ${t.name}")
                val ft = ci.fields[idx].second
                val fv = Val(emitT("load ${ft.llvm}, ptr ${emitT("getelementptr ${ci.llType}, ptr ${r.v}, i32 0, i32 ${idx + 2}")}${tbField(ft.llvm)}"), ft)
                // Reading an unassigned `lateinit` reference property throws.
                if (ft.llvm == "ptr" && (listOf(t.name) + ci.ancestors).any { "$it.$n" in lateinitFields }) {
                    val ok = label("li.ok"); val bad = label("li.unset")
                    condBr(emitT("icmp ne ptr ${fv.v}, null"), ok, bad)
                    startBlock(bad)
                    val msg = "lateinit property $n has not been initialized"
                    if (excMode) throwNew("UninitializedPropertyAccessException", sbMsg(msg))
                    else { emit("call void @rt_panic(ptr ${cstr("UninitializedPropertyAccessException: $msg")})"); emit("unreachable"); term = true }
                    startBlock(ok)
                }
                fv
            }
            is Ty.ListTy -> if (n == "size") Val(emitT("trunc i64 ${emitT("call i64 @rt_list_size(ptr ${r.v})")} to i32"), Ty.I32) else fail("unknown property $n")
            is Ty.Arr -> if (n == "size") Val(emitT("trunc i64 ${emitT("load i64, ptr ${r.v}${tbLen()}")} to i32"), Ty.I32) else fail("unknown property $n")
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

    private fun isSafeCall(e: E): Boolean = (e is MemberE && e.safe) || (e is CallE && e.callee is MemberE && (e.callee as MemberE).safe)

    /** Kotlin/Native: a negative array size throws IllegalArgumentException (catchable when exceptions are used). */
    private fun checkArrSize(sz: Val): Val {
        if (!excMode || sz.v.toLongOrNull()?.let { it >= 0 } == true) return sz
        val bad = label("arr.neg"); val ok = label("arr.ok")
        emit("br i1 ${emitT("icmp slt i64 ${sz.v}, 0")}, label %$bad, label %$ok, !prof !1"); term = true
        startBlock(bad)
        throwNew("IllegalArgumentException", Val(cstr("Negative array size"), Ty.Str))
        startBlock(ok)
        return sz
    }

    private fun canBox(t: Ty) = t != Ty.Str && boxName(t)?.let { "AnyBox$it" in classDecls } == true

    /** `v!!`: NullPointerException on null; a nullable primitive is unboxed. */
    private fun genNonNull(v: Val): Val {
        if (v.t.llvm != "ptr") return v
        val ok = label("nn.ok"); val bad = label("nn.null")
        condBr(emitT("icmp ne ptr ${v.v}, null"), ok, bad)
        startBlock(bad)
        if (excMode) throwNew("NullPointerException", Val("null", Ty.Str))
        else { emit("call void @rt_panic(ptr ${cstr("NullPointerException")})"); emit("unreachable"); term = true }
        startBlock(ok)
        return if (v.v == "null") Val("null", v.t) else if (v.t is Ty.NBox) unbox(v, v.t.inner) else v
    }

    /** `a ?: b` with a nullable primitive `a` and a non-null primitive (or jumping) `b`: the unboxed value. */
    private fun elvisBox(a: Val, r: E): Val? {
        val inner = (a.t as Ty.NBox).inner
        if (r is NullL) return null
        val lr = label("elvis.rhs"); val ln = label("elvis.lhs"); val le = label("elvis.end")
        condBr(emitT("icmp ne ptr ${a.v}, null"), ln, lr)
        startBlock(lr)
        val b0 = genExpr(r)
        if (!term && (b0.t is Ty.NBox || b0.v == "null")) fail("nullable right side of ?: on ${a.t} is not supported")
        val b = if (term) null else coerce(b0, inner)
        val rhsEnd = cur
        if (b != null) br(le)
        startBlock(ln)
        val x = unbox(Val(a.v, a.t, false), inner)
        releaseIfOwned(a)
        val lhsEnd = cur
        br(le)
        startBlock(le)
        return if (b == null) x else Val(emitT("phi ${inner.llvm} [ ${x.v}, %$lhsEnd ], [ ${b.v}, %$rhsEnd ]"), inner)
    }

    /** `==` / `!=` with a nullable primitive side: null-aware, IEEE/numeric comparison of the values. */
    private fun nboxEq(op: String, a: Val, b: Val): Val {
        val from = cur
        val lc = label("neq.chk"); val lv = label("neq.val"); val le = label("neq.end")
        val an = if (a.t is Ty.NBox) emitT("icmp eq ptr ${a.v}, null") else "false"
        val bn = if (b.t is Ty.NBox) emitT("icmp eq ptr ${b.v}, null") else "false"
        val anyNull = emitT("or i1 $an, $bn")
        val bothNull = emitT("and i1 $an, $bn")
        condBr(anyNull, le, lv)
        startBlock(lv)
        val x = if (a.t is Ty.NBox) unbox(Val(a.v, a.t, false), a.t.inner) else a
        val y = if (b.t is Ty.NBox) unbox(Val(b.v, b.t, false), b.t.inner) else b
        val c = coerce(compare("==", Val(x.v, x.t, false), Val(y.v, y.t, false)), Ty.I1).v
        val vEnd = cur
        br(le)
        startBlock(le)
        val eq = emitT("phi i1 [ $bothNull, %$from ], [ $c, %$vEnd ]")
        releaseIfOwned(a); releaseIfOwned(b)
        return Val(if (op == "==") eq else emitT("xor i1 $eq, true"), Ty.I1)
    }

    /** `recv?.x`: evaluates `f` only when recv is non-null; the result must be a pointer type. */
    private fun safe(recvE: E, f: (Val) -> Val): Val {
        val r = genExpr(recvE)
        if (r.t.llvm != "ptr") return f(r)
        val from = cur
        val lc = label("safe.call"); val le = label("safe.end")
        condBr(emitT("icmp eq ptr ${r.v}, null"), le, lc)
        startBlock(lc)
        val v0 = f(if (r.t is Ty.NBox) unbox(Val(r.v, r.t, false), r.t.inner) else r)
        // A primitive result is boxed: `a?.x` has type `Int?`.
        val v = if (v0.t.llvm != "ptr" && v0.t != Ty.Unit && canBox(v0.t)) coerce(v0, Ty.NBox(v0.t)) else v0
        if (v.t.llvm != "ptr") fail("safe call result must be a reference")
        val vFrom = cur
        br(le)
        startBlock(le)
        return Val(emitT("phi ptr [ null, %$from ], [ ${v.v}, %$vFrom ]"), v.t, v.t is Ty.NBox)
    }

    /** Stdlib scope functions with a lambda literal (`let`, `also`, `run`, `repeat`), lowered to plain blocks. */
    private fun scopeFn(e: CallE, stmt: Boolean = false): Block? {
        val body = e.lambda ?: return null
        val c = e.callee
        fun <T : E> T.l(): T = also { it.line = e.line }
        fun <T : S> T.l(): T = also { it.line = e.line }
        if (c is NameE && e.typeArgs.isEmpty() && lookup(c.n) == null && !funDecls.containsKey(c.n)) {
            if (c.n == "run" && e.args.isEmpty() && body.params.isEmpty()) return body
            if (c.n == "repeat" && e.args.size == 1 && body.params.size <= 1) {
                val p = body.params.firstOrNull() ?: "it"
                val n = "\$rep${scopeN++}"
                return Block(listOf(VarS(n, false, null, e.args[0]).l(),
                    ForS(p, BinE("..", IntL(0, false).l(), BinE("-", NameE(n).l(), IntL(1, false).l()).l()).l(), body).l()))
            }
            return null
        }
        if (c !is MemberE || c.n !in setOf("let", "also") || e.args.isNotEmpty() || e.typeArgs.isNotEmpty() || extFuns.containsKey(c.n)) return null
        if (body.params.size > 1) return null
        val p = body.params.firstOrNull() ?: "it"
        val pt = body.paramTypes.firstOrNull()
        fun inner(recv: E): Block {
            val ss = ArrayList<S>(); ss.add(VarS(p, false, pt, recv).l()); ss.addAll(body.stmts)
            if (c.n == "also") ss.add(ExprS(NameE(p).l()).l())
            return Block(ss)
        }
        if (!c.safe) return inner(c.recv)
        // `x?.let { }` only as a statement (its nullable value is not supported).
        if (!stmt) return null
        val tn = "\$sf${scopeN++}"
        return Block(listOf(VarS(tn, false, null, c.recv).l(),
            ExprS(IfE(BinE("!=", NameE(tn).l(), NullL().l()).l(), inner(NameE(tn).l()), null).l()).l()))
    }
    private var scopeN = 0

    /** Superclass of the class being compiled (for `super.f()`), or a compile error. */
    private fun superOf(): ClassInfo {
        val o = owner ?: fail("'super' outside a class")
        val sc = classDecls[o.name]?.superClass ?: fail("super calls to interface members are not supported (no superclass in ${o.name})")
        return classes[sc] ?: fail("unknown superclass $sc")
    }

    /** `super.f(args)`: direct (non-virtual) call of the superclass implementation on `this`. */
    private fun superCall(n: String, e: CallE): Val {
        val sci = superOf()
        if (sci.methods[n]?.typeParams?.isNotEmpty() == true) fail("super calls to generic methods are not supported")
        val impl = sci.impl[n] ?: fail("abstract method ${sci.name}.$n() cannot be called via super")
        val sig = methodSigs["${sci.name}.$n"] ?: fail("unknown method ${sci.name}.$n()")
        val ps = methodParams(sci, n)
        val e2 = if (ps != null && (e.args.any { it is NamedE } || allArgs(e).size < ps.size || ps.any { it.vararg })) normalize(ps, e, NameE("this").also { it.line = line }, sci) else e
        val args = methodArgs(sig, e2)
        return finishCall("call ${sig.ret.llvm} @m.$impl.$n(ptr %this${args.joinToString("") { ", ${it.t.llvm} ${it.v}" }})", sig.ret, args)
    }

    private fun genCall(e: CallE): Val {
        val callee = e.callee
        if (callee is MemberE && !callee.safe && callee.recv is NameE && (callee.recv as NameE).n == "super") return superCall(callee.n, e)
        val qn = flatten(callee)
        if (qn != null && qn in NANOS) return Val(emitT("call i64 @rt_nanos()"), Ty.I64)
        if (qn != null && (qn == "kotlin.math.sqrt" || qn == "kotlin.math.abs")) return genNamedCall(qn, e)
        scopeFn(e)?.let { return genBlockBody(it, true) }
        if (callee is NameE && lookup(callee.n)?.let { fnSig(it.t) } != null) {
            val ft = lookup(callee.n)!!.t
            // `fn()` for `fn: R.() -> X` inside a scope with an implicit receiver: `this` is the first argument.
            if (isRecvFn(ft) && fnSig(ft)!!.params.size == allArgs(e).size + 1 && lookup("this") != null)
                return invokeFn(genExpr(callee), CallE(callee, e.typeArgs, listOf(NameE("this").also { it.line = line }) + e.args, e.lambda).also { it.line = e.line })
            return invokeFn(genExpr(callee), e)
        }
        // `x.fn(args)` for a local `fn: R.(A) -> X`: invoke with `x` as the receiver argument.
        if (callee is MemberE && !callee.safe && lookup(callee.n)?.let { isRecvFn(it.t) } == true)
            return invokeFn(genExpr(NameE(callee.n).also { it.line = line }), CallE(NameE(callee.n), e.typeArgs, listOf(callee.recv) + e.args, e.lambda).also { it.line = e.line })
        if (callee is NameE && (lookup(callee.n) == null || (callee.n in classes && scopes.none { callee.n in it.vars }))) return genNamedCall(callee.n, e)
        if (callee is MemberE) {
            if (callee.safe) return safe(callee.recv) { r -> method(r, callee.n, e) }
            // `x!!.f()`: a member of the non-null type wins over an extension on the nullable type (`fun Long?.inv()`).
            if (callee.recv is UnE && callee.recv.op == "!!") {
                val r = genExpr(callee.recv)
                nonNullRecv = true
                try { return method(r, callee.n, e) } finally { nonNullRecv = false }
            }
            return method(genExpr(callee.recv), callee.n, e)
        }
        val f = genExpr(callee)
        if (fnSig(f.t) != null) return invokeFn(f, e)
        fail("unsupported call")
    }

    private val funDecls by lazy { prog.funs.associateBy { it.name } }
    private val ifaceDecls by lazy { prog.ifaces.associateBy { it.name } }

    /** Simple argument expressions that can be duplicated into default arguments. */
    private fun simpleArg(a: E) = a is NameE || a is IntL || a is BoolL || a is CharL || a is DblL || a is NullL || (a is StrL && a.parts.all { it is String })

    /**
     * Positional arguments for a call of a function with [params]: named arguments are put in place (evaluated in
     * parameter order) and missing ones filled from defaults. A default may refer to earlier parameters (their
     * arguments are substituted when simple) and, for methods, to members of [cls] through [recv].
     */
    private val normalized = HashMap<CallE, CallE>()

    /** Packs the positional arguments of a `vararg` parameter into a list (`*xs` passes a list through). */
    private fun packVararg(params: List<Param>, e: CallE): CallE {
        val vi = params.indexOfFirst { it.vararg }
        if (vi < 0) return e
        val elemT = params[vi].type.args.single()
        val named = e.args.filterIsInstance<NamedE>()
        val pos = e.args.filter { it !is NamedE }
        fun pack(items: List<E>): E {
            if (items.size == 1 && items[0] is UnE && (items[0] as UnE).op == "*") return (items[0] as UnE).e
            if (items.any { it is UnE && it.op == "*" }) fail("spread mixed with other vararg arguments is not supported")
            // A generic element type (`vararg xs: T`) is inferred from the items.
            val known = try { resolve(elemT); true } catch (x: CompileError) { false }
            if (!known && items.isEmpty()) fail("empty generic vararg is not supported")
            return CallE(NameE("arrayListOf").also { it.line = line }, if (known) listOf(elemT) else emptyList(), items, null).also { it.line = line }
        }
        val nv = named.firstOrNull { it.name == params[vi].name }
        val newPos = if (nv != null) pos else if (pos.size <= vi) (if (params[vi].default != null) pos else pos + pack(emptyList())) else pos.take(vi) + pack(pos.drop(vi))
        val newNamed = named.map { if (it === nv) NamedE(it.name, pack(listOf(it.e))).also { x -> x.line = it.line } else it }
        return CallE(e.callee, e.typeArgs, newPos + newNamed, e.lambda).also { it.line = e.line }
    }
    private fun normalize(params: List<Param>, e: CallE, recv: E? = null, cls: ClassInfo? = null): CallE =
        normalized[e] ?: normalize0(params, e, recv, cls).also { normalized[e] = it }

    private fun normalize0(params: List<Param>, e0: CallE, recv: E? = null, cls: ClassInfo? = null): CallE {
        val e = packVararg(params, e0)
        val named = e.args.filterIsInstance<NamedE>()
        if (named.isEmpty() && allArgs(e).size >= params.size) return e
        if (e !== e0 && named.isEmpty() && allArgs(e).size >= params.size) return e
        if (named.isEmpty() && params.none { it.default != null }) return e
        val slots = arrayOfNulls<E>(params.size)
        val pos = e.args.filter { it !is NamedE }
        if (pos.size > params.size) return e
        pos.forEachIndexed { i, a -> slots[i] = a }
        for (na in named) {
            val i = params.indexOfFirst { it.name == na.name }
            if (i < 0) fail("unknown parameter '${na.name}'")
            if (slots[i] != null) fail("parameter '${na.name}' passed twice")
            slots[i] = na.e
        }
        if (e.lambda != null) { if (slots[params.lastIndex] != null) fail("too many arguments"); slots[params.lastIndex] = LambdaE(e.lambda).also { it.line = line } }
        for (i in params.indices) if (slots[i] == null) {
            val d = params[i].default ?: fail("missing argument '${params[i].name}'")
            lateinit var sub: Subst
            sub = Subst(emptyMap(), rw = { x ->
                val pi = if (x is NameE) params.indexOfFirst { it.name == x.n } else -1
                when {
                    x is LambdaE -> x // keeps the closure's identity stable across compile restarts
                    x is NameE && pi >= 0 -> {
                        val a = slots[pi] ?: fail("default argument refers to a later parameter")
                        if (!simpleArg(a)) fail("default argument referring to a non-trivial argument is not supported")
                        a
                    }
                    x is NameE && recv != null && cls != null && (cls.index(x.n) >= 0 || "\$prop\$${x.n}" in cls.methods) -> MemberE(recv, x.n, false).also { it.line = line }
                    x is NameE && x.n == "this" && recv != null -> recv
                    x is CallE && x.callee is NameE && recv != null && cls != null && x.callee.n in cls.methods ->
                        CallE(MemberE(recv, x.callee.n, false).also { it.line = line }, x.typeArgs, x.args.map { sub.e(it) }, x.lambda?.let { sub.block(it) }).also { it.line = line }
                    else -> null
                }
            })
            slots[i] = sub.e(d)
        }
        return CallE(e.callee, e.typeArgs, slots.map { it!! }, null).also { it.line = e.line }
    }

    /** Parameters (with defaults) of the method [n] as declared in [ci] or the nearest supertype declaring defaults. */
    private fun methodParams(ci: ClassInfo, n: String): List<Param>? {
        val own = ci.methods[n]?.params ?: return null
        if (own.any { it.default != null }) return own
        for (s in ci.ancestors + ci.supers) {
            classes[s]?.methods?.get(n)?.params?.takeIf { ps -> ps.any { it.default != null } }?.let { return it }
            ifaceDecls[s]?.methods?.firstOrNull { it.name == n }?.params?.takeIf { ps -> ps.any { it.default != null } }?.let { return it }
        }
        return own
    }

    /** Type of [e] without emitting code (generation is rolled back), or null if it does not compile. */
    private fun probeType(e: E): Ty? {
        val sf = funcs.length; val sb = body.length; val sa = allocas.length; val sc = cur; val st = term; val sm = HashSet(moveNodes); val sr = HashSet(refVals)
        return try { genExpr(e).t } catch (x: CompileError) { null } finally {
            funcs.setLength(sf); body.setLength(sb); allocas.setLength(sa); cur = sc; term = st
            moveNodes.clear(); moveNodes.addAll(sm); refVals.clear(); refVals.addAll(sr)
        }
    }

    private val secondaryCache = HashMap<CallE, String>()

    /** The secondary-constructor factory `C$sec$k` a constructor call resolves to, or null for the primary constructor. */
    private fun secondaryFor(n: String, e: CallE): String? {
        val facs = funDecls.keys.filter { it.startsWith("$n\$sec\$") }
        if (facs.isEmpty() || e.args.any { it is NamedE }) return null
        secondaryCache[e]?.let { return it.ifEmpty { null } }
        val args = allArgs(e)
        val cands = (listOf(n to classDecls.getValue(n).ctorParams) + facs.map { it to funDecls.getValue(it).params })
            .filter { (_, ps) -> args.size in ps.count { it.default == null }..ps.size }
        val pick = if (cands.size <= 1) cands.firstOrNull()?.first ?: n else {
            val ats = args.map { probeType(it) }
            fun fits(ps: List<Param>, exact: Boolean) = ats.indices.all { i ->
                val pt = resolveOrNull(ps[i].type); val at = ats[i]
                at == null || pt == null || at == pt || at == Ty.Nothing || (!exact && (statically(at, pt) || at.isInt && pt.isInt && pt.rank >= at.rank || (at.v0isNull() && pt.llvm == "ptr")))
            }
            cands.firstOrNull { fits(it.second, true) }?.first ?: cands.firstOrNull { fits(it.second, false) }?.first ?: n
        }
        secondaryCache[e] = if (pick == n) "" else pick
        return if (pick == n) null else pick
    }
    private fun Ty.v0isNull() = this == Ty.RawPtr

    /** Calls whose context arguments were filled in already. */
    private val ctxFilled = HashSet<CallE>() // CallE has identity equality

    private fun fits(from: Ty, to: Ty): Boolean = from == to || isAny(to) ||
        (from is Ty.Cls && to is Ty.Iface && to.name in classes.getValue(from.name).supers) ||
        (from is Ty.Cls && to is Ty.Cls && to.name in classes.getValue(from.name).ancestors) ||
        (from is Ty.Iface && to is Ty.Iface && ifaceDecls[from.name]?.supers?.contains(to.name) == true)

    /** The innermost implicit value (receiver, context parameter, `this` of the class) of type [t]. */
    private fun ctxArg(t: Ty): E {
        for (i in scopes.indices.reversed()) {
            val vs = scopes[i].vars.entries.filter { it.key.startsWith("\$ctx\$") && fits(it.value.t, t) }
            if (vs.isNotEmpty()) return NameE(vs.maxBy { it.key.drop(6).toIntOrNull() ?: -1 }.key).also { it.line = line }
        }
        owner?.let { o -> if (fits(Ty.Cls(o.name), t)) return NameE("this").also { it.line = line } }
        fail("no context argument of type $t in scope")
    }

    /** [e] with the context arguments of [ps] inserted (positional, or named when [e] uses named arguments). */
    private fun withCtx(ps: List<Param>?, e: CallE, tenvFor: (TypeRef) -> Ty = { resolve(it) }): CallE? {
        if (ps == null || ps.none { it.ctx } || e in ctxFilled) return null
        val named = e.args.any { it is NamedE }
        val args = ArrayList(e.args)
        if (named) { for (p in ps) if (p.ctx) args.add(0, NamedE(p.name, ctxArg(tenvFor(p.type)))) }
        else ps.forEachIndexed { i, p -> if (p.ctx) args.add(minOf(i, args.size), ctxArg(tenvFor(p.type))) }
        return CallE(e.callee, e.typeArgs, args, e.lambda).also { it.line = e.line; ctxFilled.add(it) }
    }

    private fun genNamedCall(n: String, e00: CallE): Val {
        // A member of the implicit receiver wins over a top-level function with context parameters.
        if (lookup(n) == null && n in funDecls && n !in LiftedLocals.names && e00 !in ctxFilled) lookup("this")?.let { l ->
            val rt = l.t
            if (rt is Ty.Cls && n in classes.getValue(rt.name).methods) return method(genExpr(NameE("this").also { it.line = line }), n, e00)
        }
        val e0 = if (lookup(n) == null) withCtx(funDecls[n]?.params, e00) ?: e00 else e00
        if (n in classDecls && lookup(n) == null) secondaryFor(n, e0)?.let { return genNamedCall(it, e0) }
        owner?.let { o -> val i = o.index(n); if (i >= 0 && n !in o.methods && fnSig(o.fields[i].second) != null) return invokeFn(property(Val("%this", Ty.Cls(o.name)), n), e0) }
        owner?.let { o -> if (n in o.methods) return callMethod(Val("%this", Ty.Cls(o.name)), o, n, e0) }
        val e = (funDecls[n]?.params ?: classDecls[n]?.ctorParams)?.let { normalize(it, e0) } ?: e0
        // User-declared functions and classes shadow the built-in ones (e.g. a user `fun check`).
        if (n !in sigs && n !in classes) when (n) {
            "sqrt", "kotlin.math.sqrt", "abs", "kotlin.math.abs" -> {
                val a = genExpr(e.args.single())
                if (n.endsWith("abs") && a.t.isInt) {
                    val w = coerce(a, if (a.t == Ty.I64) Ty.I64 else Ty.I32)
                    val neg = emitT("sub ${w.t.llvm} 0, ${w.v}")
                    return Val(emitT("select i1 ${emitT("icmp slt ${w.t.llvm} ${w.v}, 0")}, ${w.t.llvm} $neg, ${w.t.llvm} ${w.v}"), w.t)
                }
                if (a.t == Ty.F32) return Val(emitT("call float @llvm.${if (n.endsWith("sqrt")) "sqrt" else "fabs"}.f32(float ${a.v})"), Ty.F32)
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
            "IntArray", "LongArray", "DoubleArray", "FloatArray", "ByteArray" -> {
                val et = when (n) { "ByteArray" -> Ty.I8; "IntArray" -> Ty.I32; "LongArray" -> Ty.I64; "FloatArray" -> Ty.F32; else -> Ty.F64 }
                if (e.lambda != null) return initLoop(e.args.single(), e.lambda, et, { sz, t -> Val(emitT("call ptr @rt_arr_new(i64 ${checkArrSize(coerce(sz, Ty.I64)).v}, i64 ${t.size})"), Ty.Arr(t), true) }) { c, i, v ->
                    emit("store ${et.llvm} ${coerce(v, et).v}, ptr ${emitT("getelementptr ${et.llvm}, ptr ${emitT("getelementptr i8, ptr ${c.v}, i64 8")}, i32 $i")}")
                }
                val size = checkArrSize(coerce(genExpr(e.args.single()), Ty.I64))
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
            "\$throw" -> if (excMode) {
                throwValue(genArg(e.args.single()))
                return Val("undef", Ty.Nothing)
            } else {
                // Without `try` in the program: `throw E(msg)` of a standard exception type terminates like an uncaught exception.
                val x = e.args.single()
                val en = ((x as? CallE)?.callee as? NameE)?.n
                if (en == null || en !in EXCEPTIONS || x.args.size > 1) fail("only throwing standard exceptions (e.g. IllegalStateException(msg)) is supported")
                val m = x.args.singleOrNull()?.let { a ->
                    val v = genExpr(a)
                    if (v.t == Ty.Str) v else { val sb = emitT("call ptr @rt_sb_new()"); appendVal("rt_sb_", "ptr $sb, ", v); Val(emitT("call ptr @rt_sb_done(ptr $sb)"), Ty.Str) }
                }
                emit("call void @rt_throw(ptr ${cstr(EXCEPTIONS.getValue(en))}, ptr ${m?.v ?: "null"})"); emit("unreachable"); term = true
                return Val("undef", Ty.Nothing)
            }
            "error", "TODO" -> {
                val m = if (e.args.isEmpty()) Val(cstr("An operation is not implemented."), Ty.Str) else coerce(genExpr(e.args[0]), Ty.Str)
                if (excMode) { throwNew(if (n == "error") "IllegalStateException" else "NotImplementedError", m); return Val("undef", Ty.Nothing) }
                emit("call void @rt_panic(ptr ${m.v})"); emit("unreachable"); term = true
                return Val("undef", Ty.Nothing)
            }
            "check", "require" -> {
                val c = coerce(genExpr(e.args[0]), Ty.I1)
                val ok = label("check.ok"); val bad = label("check.fail")
                condBr(c.v, ok, bad)
                startBlock(bad)
                val m = if (e.lambda != null) coerce(genBlockBody(e.lambda, true), Ty.Str) else Val(cstr(if (n == "check") "Check failed." else "Failed requirement."), Ty.Str)
                if (excMode) throwNew(if (n == "check") "IllegalStateException" else "IllegalArgumentException", m)
                else { emit("call void @rt_panic(ptr ${m.v})"); emit("unreachable"); term = true }
                startBlock(ok)
                return Val("", Ty.Unit)
            }
            "assertTrue", "assertFalse", "assertEquals", "assertNotEquals", "assertNull", "assertNotNull" -> {
                val a = e.args
                val (cond, msg) = when (n) {
                    "assertTrue" -> a[0] to StrL(listOf("Expected value to be true."))
                    "assertFalse" -> UnE("!", a[0]) to StrL(listOf("Expected value to be false."))
                    "assertNull" -> BinE("==", a[0], NullL()) to StrL(listOf("Expected value to be null, but was: <", a[0], ">."))
                    "assertNotNull" -> BinE("!=", a[0], NullL()) to StrL(listOf("Expected value to be not null."))
                    "assertEquals" -> BinE("==", a[0], a[1]) to StrL(listOf("Expected <", a[0], ">, actual <", a[1], ">."))
                    else -> BinE("!=", a[0], a[1]) to StrL(listOf("Illegal value: <", a[1], ">."))
                }
                listOf(cond, msg).forEach { it.line = line }
                val c = coerce(genExpr(cond), Ty.I1)
                val ok = label("assert.ok"); val bad = label("assert.fail")
                condBr(c.v, ok, bad)
                startBlock(bad)
                val m = coerce(genExpr(msg), Ty.Str)
                if (excMode) throwNew("AssertionError", m)
                else { emit("call void @rt_panic(ptr ${m.v})"); emit("unreachable"); term = true }
                startBlock(ok)
                return if (n == "assertNotNull") genExpr(UnE("!!", a[0]).also { it.line = line }) else Val("", Ty.Unit)
            }
            "assertFailsWith", "assertFails" -> if (excMode && e.lambda != null) {
                val t = e.typeArgs.singleOrNull() ?: TypeRef("Throwable", emptyList(), false)
                val v = "\$af${tmpN++}"
                val msg = StrL(listOf("Expected an exception of $t to be thrown, but was completed successfully."))
                val thr = CallE(NameE("\$throw"), emptyList(), listOf(CallE(NameE("AssertionError"), emptyList(), listOf(msg), null)), null)
                val body = Block(e.lambda.stmts + ExprS(thr).also { it.line = line })
                return genTry(TryE(body, listOf(Catch(v, t, Block(listOf(ExprS(NameE(v).also { it.line = line }).also { it.line = line })))), null), true)
            }
            "Array" -> if (e.lambda != null && e.args.size == 1) {
                val et = e.typeArgs.singleOrNull()?.let { resolve(it) }
                return initLoop(e.args[0], e.lambda, et, { _, t -> Val(emitT("call ptr @rt_list_new(ptr ${releaseFn(t) ?: "null"})"), Ty.ListTy(t), true) }) { c, _, v ->
                    emit("call void @rt_list_add(ptr ${c.v}, i64 ${toSlot(consume(v))})")
                }
            }
            "intArrayOf", "longArrayOf", "doubleArrayOf", "floatArrayOf", "byteArrayOf" -> {
                val et = when (n) { "intArrayOf" -> Ty.I32; "longArrayOf" -> Ty.I64; "doubleArrayOf" -> Ty.F64; "floatArrayOf" -> Ty.F32; else -> Ty.I8 }
                val a = emitT("call ptr @rt_arr_new(i64 ${e.args.size}, i64 ${et.size})")
                val data = emitT("getelementptr i8, ptr $a, i64 8")
                e.args.forEachIndexed { i, x -> emit("store ${et.llvm} ${coerce(genExpr(x), et).v}, ptr ${emitT("getelementptr ${et.llvm}, ptr $data, i64 $i")}") }
                return Val(a, Ty.Arr(et), true)
            }
            "ArrayList", "mutableListOf", "arrayListOf", "listOf", "arrayOf" -> {
                if (n == "ArrayList" && e.args.isNotEmpty()) fail("ArrayList(capacity) is not supported")
                val vals = e.args.map { genArg(it) }
                val et = e.typeArgs.singleOrNull()?.let { resolve(it) } ?: vals.firstOrNull()?.t ?: fail("$n needs an explicit element type, e.g. $n<Long>()")
                val rel = releaseFn(et) ?: "null"
                val l = emitT("call ptr @rt_list_new(ptr $rel)")
                for (v in vals) emit("call void @rt_list_add(ptr $l, i64 ${toSlot(consume(coerce(v, et)))})")
                return Val(l, Ty.ListTy(et), true)
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
                val all = excArgs(n, allArgs(e))
                if (all.size != cd.ctorParams.size) fail("constructor $n expects ${cd.ctorParams.size} arguments")
                val vals = all.mapIndexed { i, a -> resolve(cd.ctorParams[i].type).let { pt -> coerce(argFor(a, pt), pt) } }
                val res = Val(emitT("call ptr @ctor.$n(${vals.joinToString(", ") { "${it.t.llvm} ${it.v}" }})"), Ty.Cls(n), true)
                vals.forEach { releaseIfOwned(it) }
                afterCall()
                return res
            }
            val all = excArgs(n, allArgs(e))
            if (all.size != ci.fields.size) fail("constructor $n expects ${ci.fields.size} arguments")
            val vals = all.mapIndexed { i, a -> consume(coerce(argFor(a, ci.fields[i].second, true), ci.fields[i].second)) }
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
        if (n !in sigs) {
            // Extension (or member) call on the implicit receiver.
            if (lookup("this") != null) return method(genExpr(NameE("this").also { it.line = line }), n, e)
            owner?.let { o -> if (extFuns[n] != null) return method(Val("%this", Ty.Cls(o.name)), n, e) }
        }
        val sig = sigs[n] ?: fail("unknown function '$n'")
        val all = allArgs(e)
        if (sig.params.size != all.size) fail("$n expects ${sig.params.size} arguments")
        val vals = all.mapIndexed { i, a -> coerce(argFor(a, sig.params[i]), sig.params[i]) }
        val args = vals.filter { it.t != Ty.Args }.joinToString(", ") { "${it.t.llvm} ${it.v}" }
        val call = "call ${sig.ret.llvm} @k.$n($args)"
        val res = if (sig.ret == Ty.Unit) { emit(call); Val("", Ty.Unit) } else Val(emitT(call), sig.ret, sig.ret.isRef)
        vals.forEach { releaseIfOwned(it) }
        afterCall()
        return res
    }

    /**
     * Call of a generic function: type arguments are explicit (`f<Int>(x)`) or inferred from the argument types;
     * the call binds to the monomorphized instance `@"k.f<Int>"`, queued for generation on first use.
     * Monomorphization keeps primitives unboxed and every instance's RC code exact for its concrete types.
     */
    private fun genGenericCall(f: FunD, e: CallE, cls: ClassInfo? = null, recv: Val? = null): Val {
        if (f.params.size != allArgs(e).size) fail("${f.name} expects ${f.params.size} arguments")
        val env = LinkedHashMap<String, Ty>()
        if (e.typeArgs.isNotEmpty()) {
            if (e.typeArgs.size != f.typeParams.size) fail("${f.name} expects ${f.typeParams.size} type arguments")
            f.typeParams.forEachIndexed { i, tp -> env[tp] = resolve(e.typeArgs[i]) }
        }
        // Non-lambda arguments first (they bind type parameters), then lambdas typed from the partially bound signature.
        val all = allArgs(e)
        val rawN = arrayOfNulls<Val>(all.size)
        all.forEachIndexed { i, a -> if (a !is LambdaE) { rawN[i] = genExpr(a); if (e.typeArgs.isEmpty()) unify(f.params[i].type, rawN[i]!!.t, f.typeParams, env) } }
        all.forEachIndexed { i, a ->
            if (a !is LambdaE) return@forEachIndexed
            val pt = f.params[i].type
            fun isBound(r: TypeRef): Boolean = (r.name !in f.typeParams || r.name in env) && r.args.all { isBound(it) }
            fun res(r: TypeRef): Ty? = if (!isBound(r)) null else { val sv = tenv; tenv = sv + env; try { resolve(r) } finally { tenv = sv } }
            val isFn = pt.name == "\$Fn" || pt.name == "\$FnR"
            if (f.name in SCOPE_FUN_NAMES && hasReturn(a.body.stmts)) fail("non-local return from a '${f.name}' lambda is not supported")
            if (pt.name == "\$FnR" && pt.args.size >= 2 && !isBound(pt.args[0])) builderInfer(a.body, pt.args[0], f.typeParams, env)
            val lps = if (isFn) pt.args.dropLast(1).map { res(it) }.takeIf { l -> l.all { it != null } }?.map { it!! } else null
            val lrt = if (isFn) res(pt.args.last()) else null
            rawN[i] = genLambda(a, lps, lrt, pt.name == "\$FnR")
            unify(pt, rawN[i]!!.t, f.typeParams, env)
        }
        val raw = rawN.map { it!! }
        for (tp in f.typeParams) if (tp !in env) fail("cannot infer type parameter $tp of ${f.name}; pass it explicitly")
        val bound = f.typeParams.associateWith { env.getValue(it) }
        val sym = "\"${if (cls != null) "m.${cls.name}." else "k."}${f.name}<${f.typeParams.joinToString(",") { bound.getValue(it).toString() }}>\""
        val inst = instances[sym] ?: run {
            val saved = tenv; tenv = bound
            val sig = try {
                val ps = f.params.map { resolve(it.type) }
                FunSig(f.name, ps, f.ret?.let { resolve(it) } ?: if (f.exprBody != null) probeGenericRet(f, sym, ps, cls) else Ty.Unit)
            } finally { tenv = saved }
            if (cls != null) instCls[sym] = cls
            Triple(f, bound, sig).also { instances[sym] = it; pending.add(sym) }
        }
        val sig = inst.third
        val vals = raw.mapIndexed { i, v -> coerce(v, sig.params[i]) }
        val args = (listOfNotNull(recv?.let { "ptr ${it.v}" }) + vals.map { "${it.t.llvm} ${it.v}" }).joinToString(", ")
        return finishCall("call ${sig.ret.llvm} @$sym($args)", sig.ret, vals)
    }

    /** Builder-style receivers of standard collections: method name -> parameter types over the receiver's type parameters. */
    private val BUILDER_STD: Map<String, Pair<List<String>, Map<String, List<TypeRef>>>> by lazy {
        fun r(n: String) = TypeRef(n, emptyList(), false)
        val list = listOf("E") to mapOf("add" to listOf(r("E")))
        mapOf("MutableList" to list, "ArrayList" to list, "MutableCollection" to list, "MutableSet" to list,
            "MutableMap" to (listOf("K", "V") to mapOf("put" to listOf(r("K"), r("V")))))
    }

    /**
     * Builder inference: the receiver type [recv] of a lambda with receiver still mentions unbound type parameters of
     * the called function. Calls on the implicit receiver inside the lambda (`yield(x)`, `put(k, v)`) whose parameters
     * are typed by the receiver's type parameters bind them from the (probed) argument types.
     */
    private fun builderInfer(b: Block, recv: TypeRef, tps: List<String>, env: MutableMap<String, Ty>) {
        val (ctps, methods) = genericClasses[recv.name]?.let { cd -> cd.typeParams to cd.methods.groupBy { it.name }.mapValues { (_, fs) -> fs.first().params.map { it.type } } }
            ?: BUILDER_STD[recv.name] ?: return
        if (ctps.size != recv.args.size) return
        val sub = ctps.zip(recv.args).toMap()
        fun subst(r: TypeRef): TypeRef = sub[r.name]?.let { if (r.nullable) TypeRef(it.name, it.args, true) else it } ?: TypeRef(r.name, r.args.map { subst(it) }, r.nullable)
        val calls = ArrayList<CallE>()
        var stF: (S) -> Unit = {}
        fun ex(e: E?) {
            when (e) {
                null -> {}
                is CallE -> { if (e.callee is NameE) calls.add(e); if (e.callee !is NameE) ex(e.callee); e.args.forEach { ex(it) } }
                is NamedE -> ex(e.e)
                is BinE -> { ex(e.l); ex(e.r) }
                is UnE -> ex(e.e)
                is MemberE -> ex(e.recv)
                is IfE -> { ex(e.cond); e.th.stmts.forEach { stF(it) }; e.el?.stmts?.forEach { stF(it) } }
                else -> {}
            }
        }
        fun stImpl(s: S) {
            when (s) {
                is ExprS -> ex(s.e)
                is VarS -> ex(s.init)
                is AssignS -> ex(s.value)
                is RetS -> ex(s.e)
                else -> {}
            }
        }
        stF = ::stImpl
        b.stmts.forEach { stImpl(it) }
        for (c in calls) {
            val n = (c.callee as NameE).n
            val ps = methods[n] ?: continue
            if (ps.size != c.args.size || c.lambda != null) continue
            c.args.forEachIndexed { i, a ->
                val pr = subst(ps[i])
                fun free(r: TypeRef): Boolean = (r.name in tps && r.name !in env) || r.args.any { free(it) }
                if (!free(pr) || a is LambdaE || a is NamedE) return@forEachIndexed
                val t = probeType(a) ?: return@forEachIndexed
                if (t != Ty.Unit && t != Ty.RawPtr && t != Ty.Args) unify(pr, t, tps, env)
            }
        }
    }

    private val SCOPE_FUN_NAMES = setOf("ext\$T\$apply", "ext\$T\$also", "ext\$T\$let", "ext\$T\$run", "with")

    /** Does a block contain an unlabeled `return` outside nested lambdas (a non-local return if the lambda were inline)? */
    private fun hasReturn(ss: List<S>): Boolean {
        fun b(x: Block?) = x != null && hasReturn(x.stmts)
        fun e(x: E?): Boolean = when (x) {
            is IfE -> e(x.cond) || b(x.th) || b(x.el)
            is WhenE -> x.branches.any { b(it.second) }
            is TryE -> b(x.body) || x.catches.any { b(it.body) } || b(x.fin)
            is BinE -> e(x.l) || e(x.r)
            else -> false
        }
        return ss.any { s -> when (s) {
            is RetS -> true
            is ExprS -> e(s.e)
            is VarS -> e(s.init)
            is AssignS -> e(s.value)
            is WhileS -> b(s.body)
            is ForS -> b(s.body)
            else -> false
        } }
    }

    private val probingGeneric = HashSet<String>()

    /**
     * Return type of the generic instance [sym] of expression-bodied [f] without a declared return type: its body is
     * generated once with the instance's type bindings (already in `tenv`) in a fresh scope and the IR is rolled back.
     */
    private fun probeGenericRet(f: FunD, sym: String, ps: List<Ty>, cls: ClassInfo?): Ty {
        if (cls != null) fail("expression-bodied generic method '${f.name}' needs an explicit return type")
        if (!probingGeneric.add(sym)) fail("recursive generic function '${f.name}' needs an explicit return type")
        val sb = body.length; val sa = allocas.length; val sc = cur; val st = term; val sm = HashSet(moveNodes)
        val sf = funcs.length; val so = owner; val sr = retTy; val sl = line
        val savedScopes = ArrayList(scopes); val sts = ArrayList(tryStack); val sfs = ArrayList(finStack)
        try {
            owner = null; retTy = Ty.Unit; line = f.line
            scopes.clear(); scopes.add(Scope())
            f.params.forEachIndexed { i, p -> declare(p.name, ps[i], false, false) }
            val t = genExpr(f.exprBody!!).t
            if (t == Ty.RawPtr || t == Ty.Args) fail("expression-bodied function '${f.name}' needs an explicit return type")
            return if (t == Ty.Nothing) Ty.Unit else t
        } finally {
            probingGeneric.remove(sym)
            scopes.clear(); scopes.addAll(savedScopes); tryStack.clear(); tryStack.addAll(sts); finStack.clear(); finStack.addAll(sfs)
            body.setLength(sb); allocas.setLength(sa); cur = sc; term = st; moveNodes.clear(); moveNodes.addAll(sm)
            funcs.setLength(sf); owner = so; retTy = sr; line = sl
        }
    }

    /** `Box<Int>` -> the monomorphized class `Box$Int`; requests the instance if the program does not have it yet. */
    private fun instanceTy(n: String, args: List<TypeRef>): Ty {
        val tps = genericClasses[n]?.typeParams ?: genericIfaces.getValue(n).typeParams
        if (args.size != tps.size) fail("$n expects ${tps.size} type arguments")
        val concrete = args.map { tyRef(resolve(it)) }
        val m = mangle(n, concrete)
        if (m in classes) return Ty.Cls(m)
        if (m in ifaces) return Ty.Iface(m)
        throw NeedInstance(n, concrete)
    }

    /** Binds type parameters in [ref] against the concrete argument type [t]; the first binding wins (later args coerce). */
    private fun unify(ref: TypeRef, t: Ty, tps: List<String>, env: MutableMap<String, Ty>) {
        if (ref.name in tps) { if (ref.name !in env && t != Ty.Nothing) env[ref.name] = t; return }
        when {
            t is Ty.ListTy && ref.args.size == 1 -> unify(ref.args[0], t.elem, tps, env)
            t is Ty.MapTy && ref.args.size == 2 -> { unify(ref.args[0], t.k, tps, env); unify(ref.args[1], t.v, tps, env) }
            t is Ty.Iface && (ref.name == "\$Fn" || ref.name == "\$FnR") -> fnSig(t)?.let { s -> if (s.params.size + 1 == ref.args.size) { s.params.forEachIndexed { i, p -> unify(ref.args[i], p, tps, env) }; unify(ref.args.last(), s.ret, tps, env) } }
            t is Ty.Cls || t is Ty.Iface -> {
                val n = if (t is Ty.Cls) t.name else (t as Ty.Iface).name
                val o = instOrigin[n]
                if (o != null && o.first == ref.name && o.second.size == ref.args.size) {
                    val saved = tenv; tenv = emptyMap()
                    val tys = try { o.second.map { resolve(it) } } finally { tenv = saved }
                    ref.args.forEachIndexed { i, a -> unify(a, tys[i], tps, env) }
                } else if (t is Ty.Cls) classes[n]?.supers?.forEach { s -> unify(ref, if (s in ifaces) Ty.Iface(s) else Ty.Cls(s), tps, env) }
            }
        }
    }

    /** Top-level extension functions (`ext$Recv$name`) by name. */
    private val extFuns: Map<String, List<FunD>> by lazy { prog.funs.filter { it.name.startsWith("ext$") }.groupBy { it.name.removePrefix("ext$").substringAfter("$") } }
    private val LISTY = setOf("List", "MutableList", "ArrayList", "Array", "Collection", "MutableCollection", "Iterable")

    /** Does extension receiver type [recv] (with the extension's type parameters [tps]) accept a value of type [t]? */
    private fun extMatches(t: Ty, recv: TypeRef, tps: List<String>): Boolean {
        if (recv.name in tps) return true
        return when (t) {
            is Ty.ListTy -> recv.name in LISTY
            is Ty.MapTy -> recv.name in setOf("Map", "MutableMap", "HashMap")
            is Ty.Cls -> {
                val ci = classes.getValue(t.name)
                val names = (listOf(t.name) + ci.ancestors + ci.supers)
                recv.name in names || names.any { instOrigin[it]?.first == recv.name }
            }
            is Ty.Iface -> recv.name == t.name || instOrigin[t.name]?.first == recv.name
            else -> recv.args.isEmpty() && (try { resolve(recv) } catch (_: CompileError) { null }) == t
        }
    }

    private fun hasMember(t: Ty, n: String): Boolean = when (t) {
        is Ty.Cls -> classes.getValue(t.name).let { n in it.methods || it.index(n) >= 0 }
        is Ty.Iface -> ifaces[t.name]?.methods?.containsKey(n) == true
        else -> false
    }


    /** Classes whose instances are instances of the class or interface named [n] (generic instances included for `Box<*>`). */
    private fun instanceClasses(n: String, star: Boolean): List<String> {
        fun impl(c: String, seen: HashSet<String> = HashSet()): Set<String> {
            if (!seen.add(c)) return emptySet()
            val d = classDecls[c] ?: return setOf(c)
            return setOf(c) + d.supers.flatMap { impl(it, seen) } + (classes[c]?.ancestors ?: emptyList()).flatMap { impl(it, seen) }
        }
        val targets = if (star) (classes.keys + ifaces.keys).filter { it == n || it.startsWith("$n$") }.toSet() else setOf(n)
        return classes.keys.filter { c -> impl(c).any { it in targets } }.sorted()
    }

    /** Static upcast check: is every value of type [a] a [b]? */
    private fun statically(a: Ty, b: Ty): Boolean = a == b ||
        (a is Ty.Cls && b is Ty.Cls && b.name in classes.getValue(a.name).ancestors) ||
        (a is Ty.Cls && b is Ty.Iface && a.name in instanceClasses(b.name, false))

    private fun resolveOrNull(t: TypeRef): Ty? = try { resolve(TypeRef(t.name, t.args, false)) } catch (_: CompileError) { null }

    /** i1 for `v is t` (does not consume [v]). */
    private fun isCheck(v: Val, t: TypeRef): String {
        // A nullable primitive is null or an AnyBox: checked like an `Any?`.
        if (v.t is Ty.NBox) return isCheck(Val(v.v, Ty.Iface("Any"), false), t)
        if (Regex("Function\\d+").matches(t.name)) fail("type checks against ${t.name} are not supported")
        val star = t.args.any { it.name == "*" }
        val target = if (star) null else resolveOrNull(t)
        if (v.t.llvm != "ptr") return if (target == v.t || (target == null && t.name == "Any")) "true" else "false"
        if (v.v == "null") return if (t.nullable) "true" else "false"
        val nonNull = if (t.nullable) "true" else emitT("icmp ne ptr ${v.v}, null")
        if (t.name == "Any") return nonNull
        if (target != null && statically(v.t, target)) return nonNull
        if (!(v.t is Ty.Cls || v.t is Ty.Iface)) return if (target == v.t) nonNull else "false"
        if (isAny(v.t) && target is Ty.Iface && target.name.substringBefore('$') == "Comparable") fail("Any as Comparable is not supported")
        if (isAny(v.t) && target != null && target !is Ty.Cls && target !is Ty.Iface && boxName(target) == null) fail("type check of Any against $target is not supported")
        val boxed = if (isAny(v.t)) target?.let { boxName(it) } else null
        val special = if (target == null && !star) when (t.name) {
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

    /** Declaration line of the generic function instance being generated (key of [TypeParamBounds]); -1 outside. */
    private var instLine = -1
    /** The generic function instance being generated; null outside. */
    private var instFun: FunD? = null

    /** True if [e] is a parameter of the current generic instance typed by a type parameter whose bound is not
     *  `Double`/`Double?` (e.g. `Any`): `==` on it is boxed `equals`, which compares Doubles by bits (0.0 != -0.0). */
    private fun boxedDoubleOperand(e: E): Boolean {
        val f = instFun ?: return false
        if (e !is NameE) return false
        val p = f.params.firstOrNull { it.name == e.n } ?: return false
        if (p.type.name !in tenv) return false
        val bound = TypeParamBounds.bounds["$instLine:${p.type.name}"]
        return bound == null || bound.name !in setOf("Double", "Float")
    }

    private fun fpToInt(r: Val, target: Ty): Val {
        val w = if (target == Ty.I8) Ty.I32 else target
        val fn = "llvm.${if (w.isUnsigned) "fptoui" else "fptosi"}.sat.${w.llvm}.${if (r.t == Ty.F32) "f32" else "f64"}"
        val v = Val(emitT("call ${w.llvm} @$fn(${r.t.llvm} ${r.v})"), w)
        return if (target == Ty.I8) Val(emitT("trunc i32 ${v.v} to i8"), Ty.I8) else v
    }

    private fun doubleBits(v0: Val): String {
        val v = coerce(v0, Ty.F64)
        val b = emitT("bitcast double ${v.v} to i64")
        val nan = emitT("fcmp uno double ${v.v}, ${v.v}")
        return emitT("select i1 $nan, i64 9221120237041090560, i64 $b")
    }

    private fun genAs(e0: AsE): Val {
        // `x as T` with an unbounded type parameter (implicit bound `Any?`) is unchecked: a null passes through.
        val e = if (instLine >= 0 && e0.type.name in tenv && !e0.type.nullable && !e0.safe && "$instLine:${e0.type.name}" in TypeParamBounds.unbounded)
            AsE(e0.e, TypeRef(e0.type.name, e0.type.args, true), false).also { it.line = e0.line } else e0
        val v = genExpr(e.e)
        if (e.type.name == "Any" && "Any" in ifaces && (boxName(v.t) != null || v.t is Ty.Iface || v.t is Ty.Cls && v.t.name.substringBefore('$') !in genericClasses)) return coerce(v, Ty.Iface("Any"))
        val star = e.type.args.any { it.name == "*" }
        val target = if (e.type.name == "Any") v.t else if (star) (v.t.takeIf { v.t is Ty.Cls || v.t is Ty.Iface } ?: fail("unsupported cast to ${e.type.name}<*>")) else resolve(TypeRef(e.type.name, e.type.args, false))
        if (v.v == "null" && target.llvm != "ptr") fail("unchecked cast of null to non-nullable $target is not supported")
        if (v.t.llvm != "ptr") {
            if (v.t == target) return v
            fail("unsupported cast from ${v.t} to $target")
        }
        if (v.v == "null") {
            if (e.type.nullable || e.safe) return Val("null", target)
        }
        if (statically(v.t, target) && (e.type.nullable || e.safe) && v.v != "null") return Val(v.v, target, v.owned)
        val unboxing = isAny(v.t) && boxName(target) != null
        if (unboxing && (e.safe || e.type.nullable)) fail("nullable cast from Any to $target is not supported")
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
        condBr(ok, lok, lbad)
        startBlock(lbad)
        releaseIfOwned(v)
        val msg = "class ${v.t} cannot be cast to class ${e.type.name}"
        if (excMode) throwNew("ClassCastException", Val(cstr(msg), Ty.Str))
        else { emit("call void @rt_panic(ptr ${cstr("ClassCastException: $msg")})"); emit("unreachable"); term = true }
        startBlock(lok)
        return if (unboxing) unbox(v, target) else Val(v.v, target, v.owned)
    }

    /** Smart casts implied by [cond] being true (positive) or false: local name -> narrowed type. */
    private fun smartCasts(cond: E, positive: Boolean): List<Pair<String, TypeRef>> = when {
        cond is IsE && cond.e is NameE && cond.neg != positive && cond.type.args.none { it.name == "*" } && cond.type.name != "Any" -> listOf(cond.e.n to cond.type)
        // `x != null` narrows a nullable primitive local to its value.
        cond is BinE && ((cond.op == "!=" && positive) || (cond.op == "==" && !positive)) && cond.l is NameE && cond.r is NullL -> listOf(cond.l.n to TypeRef("\$nn", emptyList(), false))
        cond is BinE && cond.op == "&&" && positive -> smartCasts(cond.l, true) + smartCasts(cond.r, true)
        cond is BinE && cond.op == "||" && !positive -> smartCasts(cond.l, false) + smartCasts(cond.r, false)
        cond is UnE && cond.op == "!" -> smartCasts(cond.e, !positive)
        else -> emptyList()
    }

    /** Slots of aliases made by `x is Int?`-style smart casts (unboxed although the value may be null). */
    private val nullableAliases = HashSet<String>()
    private fun nullableAlias(e: E) = e is NameE && lookup(e.n)?.slot?.let { it in nullableAliases } == true

    /** Declares borrowed narrowed aliases of the [casts] locals in the current scope (skipping reassigned vars). */
    private fun applyCasts(casts: List<Pair<String, TypeRef>>) {
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
            if (isAny(l.t) && boxName(t) != null) {
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

    private fun isData(t: Ty) = t is Ty.Cls && "\$equals" in classes.getValue(t.name).methods

    /** Generated members (`copy`, default `equals`/`hashCode`/`toString`) and built-in `hashCode`/`componentN`; null if not one. */
    private fun dataMethod(r: Val, n: String, e: CallE): Val? {
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
                        val args = cd.ctorParams.mapIndexed { i, p -> pos.getOrNull(i) ?: named[p.name]?.e ?: MemberE(x, p.name, false).also { it.line = line } }
                        genNamedCall(t.name, CallE(NameE(t.name), emptyList(), args, null).also { it.line = line })
                    }
                }
                "equals" -> return compare("==", r, genExpr(e.args.single()))
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
            val lt = coerce(compare("<", r, o), Ty.I1).v; val gt = coerce(compare(">", r, o), Ty.I1).v
            return Val(emitT("select i1 $lt, i32 -1, i32 ${emitT("zext i1 $gt to i32")}"), Ty.I32)
        }
        if (n == "hashCode" && e.args.isEmpty()) when {
            t == Ty.Str -> { val v = Val(emitT("call i32 @rt_str_hash(ptr ${r.v})"), Ty.I32); releaseIfOwned(r); return v }
            t == Ty.I1 -> return Val(emitT("select i1 ${r.v}, i32 1231, i32 1237"), Ty.I32)
            t == Ty.F64 -> return Val(emitT("call i32 @rt_double_hash(double ${r.v})"), Ty.I32)
            t == Ty.F32 -> return Val(emitT("select i1 ${emitT("fcmp uno float ${r.v}, ${r.v}")}, i32 2143289344, i32 ${emitT("bitcast float ${r.v} to i32")}"), Ty.I32)
            t == Ty.I64 || t == Ty.U64 -> { val x = emitT("xor i64 ${r.v}, ${emitT("lshr i64 ${r.v}, 32")}"); return Val(emitT("trunc i64 $x to i32"), Ty.I32) }
            t == Ty.Char || t.isInt -> return Val(coerce(Val(r.v, if (t == Ty.Char) Ty.I32 else t), Ty.I32).v, Ty.I32)
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
                val x = coerce(method(ev, "hashCode", CallE(e.callee, emptyList(), emptyList(), null).also { it.line = line }), Ty.I32)
                val fromN = cur
                br(lj); startBlock(lj)
                emitT("phi i32 [ 0, %$from ], [ ${x.v}, %$fromN ]")
            } else coerce(method(ev, "hashCode", CallE(e.callee, emptyList(), emptyList(), null).also { it.line = line }), Ty.I32).v
            emit("store i32 ${emitT("add i32 ${emitT("mul i32 ${emitT("load i32, ptr $h")}, 31")}, $eh")}, ptr $h")
            emit("store i64 ${emitT("add i64 $i, 1")}, ptr $idx")
            br(lc); startBlock(le)
            val v = Val(emitT("load i32, ptr $h"), Ty.I32)
            releaseIfOwned(r); return v
        }
        if (t is Ty.ListTy && e.args.isEmpty() && n.startsWith("component") && n.removePrefix("component").toIntOrNull() != null)
            return method(r, "get", CallE(e.callee, emptyList(), listOf(IntL(n.removePrefix("component").toLong() - 1, false)), null).also { it.line = line })
        return null
    }

    /** `x++` with an `operator fun T?.inc()` extension: the operator call resolves to the extension. */
    private var incViaExt = false

    /** Set while generating a call whose receiver is `x!!` (see [method]). */
    private var nonNullRecv = false
    private fun method(r: Val, n: String, e0: CallE): Val {
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
        // Member extension of the enclosing class (dispatch receiver `this`).
        owner?.let { o -> o.methods["mext\$$n"]?.takeIf { extMatches(t, it.params[0].type, emptyList()) && !hasMember(t, n) }?.let {
            if (e0.args.any { a -> a is NamedE }) fail("named arguments in member extension calls are not supported")
            return withTemps(listOf(r)) { (x) -> callMethod(Val("%this", Ty.Cls(o.name)), o, "mext\$$n", CallE(NameE("mext\$$n"), e0.typeArgs, listOf(x) + e0.args, e0.lambda).also { it.line = line }) }
        } }
        if (isAny(t) && n == "toString" && e0.args.isEmpty()) { val s = anyCall("anyToString__", listOf(Val(r.v, t, false))); releaseIfOwned(r); return s }
        if (isAny(t) && n == "equals" && e0.args.size == 1) return compare("==", r, genExpr(e0.args[0]))
        if (isAny(t) && n == "hashCode" && e0.args.isEmpty()) { val h = anyCall("anyHashCode__", listOf(Val(r.v, t, false))); releaseIfOwned(r); return h }
        // Internal: canonical bits of a Double (all NaNs equal), used by data class equals.
        if (t.isFp && n == "\$bits" && e0.args.isEmpty()) {
            if (t == Ty.F32) return Val(doubleBits(r), Ty.I64)
            val b = emitT("bitcast double ${r.v} to i64")
            val nan = emitT("fcmp uno double ${r.v}, ${r.v}")
            return Val(emitT("select i1 $nan, i64 9221120237041090560, i64 $b"), Ty.I64)
        }
        // Operators of numbers and strings called as methods: `1.plus(2)`, `"a".plus("b")`, `1.rangeTo(3)`, `x.equals(y)`.
        val opOf = mapOf("plus" to "+", "minus" to "-", "times" to "*", "div" to "/", "rem" to "%", "rangeTo" to "..", "equals" to "==")
        // `equals` is boxed equality: false for another type; Double.equals compares bits (0.0 != -0.0), not supported.
        if (n == "equals" && t.isFp && e0.args.size == 1) fail("$t.equals() is not supported")
        if (n == "equals" && (t == Ty.I32 || t == Ty.I64 || t == Ty.U32 || t == Ty.U64 || t == Ty.Char || t == Ty.Str) && e0.args.size == 1 && e0.args[0] !is NamedE &&
            extFuns[n]?.none { extMatches(t, it.params[0].type, it.typeParams) } != false) {
            val a = genExpr(e0.args[0])
            if (a.t is Ty.NBox && a.t.inner == t) return nboxEq("==", r, a)
            if (a.t != t) { releaseIfOwned(a); releaseIfOwned(r); return Val("false", Ty.I1) }
            return compare("==", r, a)
        }
        if (n != "equals" && (t == Ty.I32 || t == Ty.I64 || t == Ty.U32 || t == Ty.U64 || t.isFp || t == Ty.Char || (t == Ty.Str && (n == "plus" || n == "equals"))) &&
            n in opOf && e0.args.size == 1 && e0.args[0] !is NamedE && e0.lambda == null && (e0 !is CallE || !e0.infix || extFuns[n]?.none { extMatches(t, it.params[0].type, it.typeParams) } != false))
            return withTemps(listOf(r)) { (x) -> genExpr(BinE(opOf.getValue(n), x, e0.args[0]).also { it.line = line }) }
        // Built-in members `inc()`/`dec()` of numbers and chars win over extensions.
        // Byte (I8) is excluded: its inc() must wrap at 8 bits.
        if ((t == Ty.I32 || t == Ty.I64 || t == Ty.U32 || t == Ty.U64 || t == Ty.Char) && (n == "inc" || n == "dec") && e0.args.isEmpty() && !incViaExt)
            return withTemps(listOf(r)) { (x) -> genExpr(BinE(if (n == "inc") "+" else "-", x, IntL(1, false)).also { it.line = line }) }
        val nn = nonNullRecv; nonNullRecv = false
        // Bitwise members called as methods: `x.shl(2)`, `x.inv()` (they win over an extension when the receiver is `y!!`).
        if ((t == Ty.I32 || t == Ty.I64 || t == Ty.U32 || t == Ty.U64) && (nn || extFuns[n]?.none { extMatches(t, it.params[0].type, it.typeParams) } != false) && e0.lambda == null && e0.args.none { it is NamedE }) {
            if (n in setOf("and", "or", "xor", "shl", "shr", "ushr") && e0.args.size == 1)
                return withTemps(listOf(r)) { (x) -> genExpr(BinE(n, x, e0.args[0]).also { it.line = line }) }
            if (n == "inv" && e0.args.isEmpty())
                return withTemps(listOf(r)) { (x) -> genExpr(BinE("xor", x, UnE("-", IntL(1, t == Ty.I64 || t == Ty.U64)).also { it.line = line }).also { it.line = line }) }
        }
        if (!hasMember(t, n)) extFuns[n]?.firstOrNull { extMatches(t, it.params[0].type, it.typeParams) && !(nn && it.params[0].type.nullable && t.llvm != "ptr") }?.let { f ->
            return withTemps(listOf(r)) { (x) -> genNamedCall(f.name, CallE(NameE(f.name), e0.typeArgs, listOf(x) + e0.args, e0.lambda).also { it.line = line }) }
        }
        dataMethod(r, n, e0)?.let { return it }
        if (t is Ty.ListTy && n in setOf("map", "filter", "forEach", "sumOf", "count") && e0.lambda == null && e0.args.size == 1 && e0.args[0] !is NamedE) {
            // A function value or callable reference argument becomes an inline lambda `{ f(it) }`.
            val a = e0.args[0]
            val it0 = NameE("it").also { it.line = line }
            val tn = ((a as? UnE)?.e as? NameE)?.n
            val call: E = when {
                a is UnE && a.op.startsWith("::") && a.e is NullL && a.op.drop(2) in funDecls -> CallE(NameE(a.op.drop(2)), emptyList(), listOf(it0), null)
                a is UnE && a.op.startsWith("::") && tn != null && lookup(tn) == null && tn in classes -> {
                    val m = a.op.drop(2)
                    if (m in classes.getValue(tn).methods && !m.startsWith("\$prop\$")) CallE(MemberE(it0, m, false), emptyList(), emptyList(), null) else MemberE(it0, m, false)
                }
                else -> CallE(a, emptyList(), listOf(it0), null)
            }.also { it.line = line }
            return method(r, n, CallE(e0.callee, e0.typeArgs, emptyList(), Block(listOf(ExprS(call).also { it.line = line }))).also { it.line = e0.line })
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
            t.isNum && n in setOf("toInt", "toUInt", "toLong", "toULong", "toByte", "toDouble", "toFloat", "toString") -> {
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
            t == Ty.Str && n == "toString" -> return r
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

    private fun methodArgs(sig: FunSig, e: CallE): List<Val> {
        val all = allArgs(e)
        if (sig.params.size != all.size) fail("${sig.name} expects ${sig.params.size} arguments")
        return all.mapIndexed { i, a -> coerce(argFor(a, sig.params[i]), sig.params[i]) }
    }

    /** Call arguments including a trailing lambda (`f(x) { ... }`). */
    private fun allArgs(e: CallE): List<E> = if (e.lambda != null) e.args + LambdaE(e.lambda) else e.args

    /** An argument with a known parameter type: lambdas get their parameter/return types from a function type. */
    private fun argFor(a: E, t: Ty, own: Boolean = false): Val =
        if (a is LambdaE) fnSig(t).let { s -> genLambda(a, s?.params, s?.ret, isRecvFn(t)) } else if (own) genArg(a) else genExpr(a)

    private fun isRecvFn(t: Ty) = t is Ty.Iface && t.name.startsWith("\$FnR")

    /** The `invoke` signature if [t] is a function type. */
    private fun fnSig(t: Ty): FunSig? = (t as? Ty.Iface)?.takeIf { it.name.startsWith("\$Fn") }?.let { ifaces[it.name]?.methods?.get("invoke") }

    /** Function type `(ps) -> r`: the interface `$Fn$...` with one `invoke` method (requested if not in the program yet). */
    /** Function type; [receiver]: `R.(A) -> X` (a distinct `$FnR...` interface whose first parameter is `this`). */
    private fun fnTy(ps: List<Ty>, r: Ty, receiver: Boolean = false): Ty.Iface {
        val name = mangle(if (receiver) "\$FnR" else "\$Fn", (ps + r).map { tyRef(it) })
        if (name in ifaces) return Ty.Iface(name)
        throw NeedIface(InterfaceD(name, listOf(FunD("invoke", ps.mapIndexed { i, t -> Param("p$i", tyRef(t)) }, tyRef(r), null, null, 0)), 0))
    }

    private fun invokeFn(f: Val, e: CallE): Val {
        val ii = ifaces.getValue((f.t as Ty.Iface).name)
        val v = callIface(f, ii, "invoke", e)
        releaseIfOwned(f)
        return v
    }

    /**
     * Lambda literal -> closure object: a synthesized class `$Lam<id>$<types>` whose fields are the captured locals
     * (by value; captured `var`s were boxed into `$Ref` cells by the frontend) and, inside a class, `$outer` (`this`),
     * implementing the function-type interface with an `invoke` method holding the body. Captures are retained into
     * the closure and released with it, so Perceus RC and the cycle collector treat closures like any object.
     */
    /**
     * Callable reference `::f`, `::C` (constructor), `C::m` / `C::p` (unbound: receiver is the first parameter) and
     * `x::m` (bound: receiver evaluated once): lowered to an equivalent lambda with typed parameters.
     */

    private val refVals = HashSet<String>()

    private fun genRef(node: UnE, recv: E?, n: String): Val = genRef0(node, recv, n).also { refVals.add(it.v) }

    private fun genRef0(node: UnE, recv: E?, n: String): Val {
        // The synthesized lambda is cached per reference node so repeated (dry-run) generation reuses one closure class.
        fun lam(params: List<TypeRef>, body: (List<E>) -> E): Val {
            if (params.size > 22) fail("references with more than 22 parameters are not supported")
            val le = RefLambdas.map.getOrPut(node) {
                val names = params.indices.map { "rp\$$it" }
                val call = body(names.map { NameE(it).also { x -> x.line = line } })
                LambdaE(Block(listOf(ExprS(call).also { it.line = line }), names, params)).also { it.line = line }
            }
            return genExpr(le)
        }
        fun callOn(r: E, name: String, args: List<E>, isProp: Boolean): E =
            if (isProp) MemberE(r, name, false).also { it.line = line }
            else CallE(MemberE(r, name, false).also { it.line = line }, emptyList(), args, null).also { it.line = line }
        fun memberParams(t: Ty, name: String): Pair<List<TypeRef>, Boolean>? = when (t) {
            is Ty.Cls -> classes.getValue(t.name).let { ci ->
                ci.methods[name]?.let { m -> if (m.typeParams.isNotEmpty()) fail("reference to generic method $name is not supported"); m.params.map { it.type } to false }
                    ?: if (ci.index(name) >= 0 || "\$prop\$$name" in ci.methods) emptyList<TypeRef>() to true else null
            }
            is Ty.Iface -> ifaceDecls[t.name]?.methods?.firstOrNull { it.name == name }?.let { m -> m.params.map { it.type } to false }
                ?: ifaceDecls[t.name]?.methods?.firstOrNull { it.name == "\$prop\$$name" }?.let { emptyList<TypeRef>() to true }
            else -> null
        }
        if (recv == null) {
            funDecls[n]?.let { f ->
                if (f.typeParams.isNotEmpty()) fail("reference to generic function $n is not supported")
                return lam(f.params.map { it.type }) { xs -> CallE(NameE(n).also { it.line = line }, emptyList(), xs, null).also { it.line = line } }
            }
            classDecls[n]?.takeIf { it.typeParams.isEmpty() && it.enumEntries == null }?.let { c ->
                return lam(c.ctorParams.map { it.type }) { xs -> CallE(NameE(n).also { it.line = line }, emptyList(), xs, null).also { it.line = line } }
            }
            owner?.let { o -> if (n in o.methods || o.index(n) >= 0) return genRef(node, NameE("this").also { it.line = line }, n) }
            fail("unsupported callable reference ::$n")
        }
        // Unbound reference through a type name.
        val typeName = (recv as? NameE)?.n?.takeIf { lookup(it) == null && "\$get\$$it" !in sigs && (it in classes || it in ifaces) && !(it == "this") }
        if (typeName != null) {
            val t = resolve(TypeRef(typeName, emptyList(), false))
            val (ps, isProp) = memberParams(t, n) ?: fail("unknown member $typeName::$n")
            return lam(listOf(TypeRef(typeName, emptyList(), false)) + ps) { xs -> callOn(xs[0], n, xs.drop(1), isProp) }
        }
        // Bound reference: the receiver is evaluated once and captured.
        val r = genExpr(recv)
        val (ps, isProp) = memberParams(r.t, n) ?: fail("unsupported callable reference ${r.t}::$n")
        scopes.add(Scope())
        val l = declare("\$refRecv", r.t, false, r.owned && r.t.isRef)
        emit("store ${r.t.llvm} ${r.v}, ptr ${l.slot}")
        val res = consume(lam(ps) { xs -> callOn(NameE("\$refRecv").also { it.line = line }, n, xs, isProp) })
        releaseScope(scopes.last()); scopes.removeAt(scopes.size - 1)
        return res
    }

    private fun genLambda(e: LambdaE, ps: List<Ty>?, ret: Ty?, receiver: Boolean = false, asRecvFn: Boolean = false): Val {
        val b0 = e.body
        val declared = b0.params
        if (receiver) {
            // Lambda with receiver: the first parameter is `this` (implicit receiver of unqualified calls).
            if (ps == null || ps.isEmpty()) fail("lambda with receiver needs a known receiver type")
            val rest = ps.drop(1)
            val names = if (declared.isEmpty()) (if (rest.size == 1) listOf("it") else if (rest.isEmpty()) emptyList() else fail("lambda needs ${rest.size} parameters")) else declared
            if (names.size != rest.size) fail("lambda expects ${rest.size} parameters, has ${names.size}")
            if (b0.paramTypes.any { it != null }) fail("typed parameters in a lambda with receiver are not supported")
            val b1 = RecvLambdas.map.getOrPut(b0) { Block(b0.stmts, listOf("this") + names.mapIndexed { i, n -> if (n == "_") "\$_$i" else n }, emptyList()) }
            return genLambda(LambdaE(b1).also { it.line = e.line }, ps, ret, false, asRecvFn = true)
        }
        val ptys: List<Ty> = if (declared.isEmpty()) {
            if (ps == null || ps.size <= 1) ps ?: emptyList() else fail("lambda needs ${ps.size} parameters")
        } else declared.mapIndexed { i, n -> b0.paramTypes.getOrNull(i)?.let { resolve(it) } ?: ps?.getOrNull(i) ?: fail("cannot infer the type of lambda parameter '$n'") }
        if (ps != null && ps.size != ptys.size) fail("lambda expects ${ps.size} parameters, has ${ptys.size}")
        val pnames = if (declared.isEmpty()) (if (ptys.size == 1) listOf("it") else emptyList()) else declared.mapIndexed { i, n -> if (n == "_") "\$_$i" else n }
        val free = LinkedHashSet<String>()
        for (s in b0.stmts) for (u in Uses.ofStmt(s)) free.add(u.node.n)
        val caps = ArrayList<Pair<String, Ty>>()
        var outer = false
        val o = owner
        for (n in free) {
            if (n in pnames) continue
            val l = scopes.reversed().firstNotNullOfOrNull { it.vars[n] }
            if (l != null) {
                if (l.t == Ty.Args || l.t == Ty.Pinned) fail("cannot capture '$n' in a lambda")
                caps.add(n to l.t); continue
            }
            if (o != null && (n == "this" || o.index(n) >= 0 || n in o.methods || "\$prop\$$n" in o.methods)) outer = true
        }
        val capNames = caps.map { it.first }.toSet()
        val lamBody = if (!outer) b0 else b0.rewritten ?: run {
            val oc = o!!
            Subst(emptyMap(), { x ->
                when {
                    x is NameE && x.n == "this" -> NameE("\$outer")
                    x is NameE && x.n !in pnames && x.n !in capNames && (oc.index(x.n) >= 0 || "\$prop\$${x.n}" in oc.methods) -> MemberE(NameE("\$outer"), x.n, false)
                    x is NameE && x.n !in pnames && x.n !in capNames && x.n in oc.methods -> MemberE(NameE("\$outer"), x.n, false)
                    else -> null
                }?.also { it.line = x.line }
            }).block(b0)
        }.also { b0.rewritten = it }
        if (outer) caps.add("\$outer" to Ty.Cls(o!!.name))
        val rt = when {
            ret == Ty.Unit -> Ty.Unit
            ret != null -> ret
            else -> {
                // Probe the body's type in the current context; the probe's IR is rolled back.
                val sb = body.length; val sa = allocas.length; val sc = cur; val st = term; val sm = HashSet(moveNodes)
                scopes.add(Scope())
                pnames.forEachIndexed { i, n -> declare(n, ptys[i], false, false) }
                val t = try { genBlockBody(b0, true).t } finally {
                    scopes.removeAt(scopes.size - 1)
                    body.setLength(sb); allocas.setLength(sa); cur = sc; term = st; moveNodes.clear(); moveNodes.addAll(sm)
                }
                if (t == Ty.Nothing) Ty.Unit else t
            }
        }
        val fnT = fnTy(ptys, rt, asRecvFn)
        val name = mangle("\$Lam${LamIds.id(b0)}", (caps.map { it.second } + ptys + rt).map { tyRef(it) })
        if (name !in classes) {
            val m = FunD("invoke", pnames.mapIndexed { i, n -> Param(n, tyRef(ptys[i])) }, tyRef(rt), null,
                WhenE(null, listOf(null to lamBody)).also { it.line = line }, line)
            throw NeedClass(ClassD(name, caps.map { (n, t) -> FieldD(n, tyRef(t), false) }, listOf(fnT.name), listOf(m), line))
        }
        val v = genNamedCall(name, CallE(NameE(name), emptyList(), caps.map { (n, _) -> NameE(if (n == "\$outer") "this" else n).also { it.line = line } }, null))
        return Val(v.v, fnT, v.owned)
    }

    private fun finishCall(call: String, ret: Ty, args: List<Val>): Val {
        val res = if (ret == Ty.Unit) { emit(call); Val("", Ty.Unit) } else Val(emitT(call), ret, ret.isRef)
        args.forEach { releaseIfOwned(it) }
        afterCall()
        return res
    }

    /** Statically bound call of a class's own method. */
    private fun callMethod(r: Val, ci: ClassInfo, n: String, e00: CallE): Val {
        val ps = methodParams(ci, n)
        val e0 = withCtx(ps, e00) ?: e00
        if (ps != null && (e0.args.any { it is NamedE } || allArgs(e0).size < ps.size || ps.any { it.vararg })) {
            if (r.v == "%this") return callMethod0(r, ci, n, normalize(ps, e0, NameE("this").also { it.line = line }, ci))
            // The receiver is visible to default arguments as `$recv` (a fixed name keeps [normalize]'s memo valid).
            scopes.add(Scope())
            val l = declare("\$recv", r.t, false, false)
            emit("store ptr ${r.v}, ptr ${l.slot}")
            try { return callMethod0(r, ci, n, normalize(ps, e0, NameE("\$recv").also { it.line = line }, ci)) } finally { scopes.removeAt(scopes.size - 1) }
        }
        return callMethod0(r, ci, n, e0)
    }

    private fun callMethod0(r: Val, ci: ClassInfo, n: String, e: CallE): Val {
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
    private fun callIface(r: Val, ii: IfaceInfo, n: String, e0: CallE): Val {
        val sig = ii.methods[n] ?: fail("unknown method ${ii.name}.$n()")
        val e = ifaceDecls[ii.name]?.methods?.firstOrNull { it.name == n }?.let { normalize(it.params, e0) } ?: e0
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

    /** Synthetic call node for an operator method with at most one argument. */
    private fun opCall(n: String, arg: E?): CallE = CallE(NameE(n), emptyList(), listOfNotNull(arg), null)

    private fun genBin(e: BinE): Val {
        when (e.op) {
            "&&", "||" -> {
                val a = coerce(genExpr(e.l), Ty.I1)
                val from = cur
                val lr = label("logic.rhs"); val le = label("logic.end")
                if (e.op == "&&") condBr(a.v, lr, le) else condBr(a.v, le, lr)
                startBlock(lr)
                scopes.add(Scope()); applyCasts(smartCasts(e.l, e.op == "&&"))
                val b = try { coerce(genExpr(e.r), Ty.I1) } finally { scopes.removeAt(scopes.size - 1) }
                val bFrom = cur
                if (term) { startBlock(le); return Val(if (e.op == "&&") "false" else "true", Ty.I1) } // rhs never completes
                br(le)
                startBlock(le)
                return Val(emitT("phi i1 [ ${if (e.op == "&&") "false" else "true"}, %$from ], [ ${b.v}, %$bFrom ]"), Ty.I1)
            }
            "?:" -> {
                val a = genExpr(e.l)
                if (a.t.llvm != "ptr") return a
                if (a.t is Ty.NBox) elvisBox(a, e.r)?.let { return it }
                val lr = label("elvis.rhs"); val ln = label("elvis.lhs"); val le = label("elvis.end")
                condBr(emitT("icmp ne ptr ${a.v}, null"), ln, lr)
                startBlock(lr)
                val b0 = genExpr(e.r)
                var b: Val? = null
                if (!term) {
                    b = coerce(b0, a.t)
                    // Both arms must agree on ownership: the result is owned if either side is.
                    if (a.owned && !b.owned && b.v != "null" && a.t.isRef) retain(b.v, a.t)
                    b = Val(b.v, b.t, b.owned)
                }
                val rhsEnd = cur
                if (b != null) br(le)
                startBlock(ln)
                val owned = a.owned || (b?.owned == true && a.t.isRef)
                if (owned && !a.owned) retain(a.v, a.t)
                val lhsEnd = cur
                br(le)
                startBlock(le)
                return if (b == null) Val(a.v, a.t, a.owned) else Val(emitT("phi ptr [ ${a.v}, %$lhsEnd ], [ ${b.v}, %$rhsEnd ]"), a.t, owned)
            }
            "..", "until", "downTo", "step" -> return rangeValue(e)
            "in", "!in" -> {
                val v = inRange(e.l, e.r)
                return if (e.op == "in") v else Val(emitT("xor i1 ${v.v}, true"), Ty.I1)
            }
        }
        val a = genExpr(e.l)
        if ((e.op == "==" || e.op == "!=") && a.t is Ty.NBox && isSafeCall(e.l) && e.r !is NullL) {
            // `a?.x == f()`: when `a` is null the right side is not evaluated (as Kotlin/Native does).
            val from = cur; val lv = label("seq.val"); val le = label("seq.end")
            condBr(emitT("icmp eq ptr ${a.v}, null"), le, lv)
            startBlock(lv)
            val c = nboxEq(e.op, a, genExpr(e.r)); val vEnd = cur
            br(le); startBlock(le)
            return Val(emitT("phi i1 [ ${e.op == "!="}, %$from ], [ ${c.v}, %$vEnd ]"), Ty.I1)
        }
        if (a.t is Ty.Cls || a.t is Ty.Iface) {
            // Operator overloading: `a + b` -> a.plus(b); `a < b` -> a.compareTo(b) < 0. `==` stays identity.
            OP_FUNS[e.op]?.let { return method(a, it, opCall(it, e.r)) }
            if (e.op in setOf("<", ">", "<=", ">=")) return compare(e.op, method(a, "compareTo", opCall("compareTo", e.r)), Val("0", Ty.I32))
        }
        val b = genExpr(e.r)
        if (a.t == Ty.Char || b.t == Ty.Char) return charOp(e.op, a, b)
        // `Double? == Int?` after nullable smart casts: null handling plus numeric promotion is not modelled yet.
        if ((e.op == "==" || e.op == "!=") && a.t != b.t && a.t.isNum && b.t.isNum && (nullableAlias(e.l) || nullableAlias(e.r)))
            fail("equality of smart-cast nullable ${a.t} and ${b.t} is not supported")
        if ((e.op == "==" || e.op == "!=") && a.t.isFp && b.t.isFp && (boxedDoubleOperand(e.l) || boxedDoubleOperand(e.r)))
            return compare(e.op, Val(doubleBits(a), Ty.I64), Val(doubleBits(b), Ty.I64))
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

    /**
     * `Array(n) { i -> init }` / `IntArray(n) { ... }`: creates the container with [mk] (element type [hint] or the
     * init block's type, probed with rollback), then runs the block inline for each index and stores with [put].
     */
    private fun initLoop(sizeE: E, lam: Block, hint: Ty?, mk: (Val, Ty) -> Val, put: (Val, String, Val) -> Unit): Val {
        val n = coerce(genExpr(sizeE), Ty.I32)
        val pn = lam.params.firstOrNull() ?: "it"
        val et = hint ?: run {
            val sb = body.length; val sa = allocas.length; val sc = cur; val st = term; val sm = HashSet(moveNodes)
            scopes.add(Scope()); declare(pn, Ty.I32, false, false)
            val t = try { genBlockBody(lam, true).t } finally {
                scopes.removeAt(scopes.size - 1)
                body.setLength(sb); allocas.setLength(sa); cur = sc; term = st; moveNodes.clear(); moveNodes.addAll(sm)
            }
            if (t == Ty.Unit || t == Ty.Nothing) fail("array initializer must yield a value")
            t
        }
        val c = mk(n, et)
        val idx = "%l.ai.${tmpN++}"
        allocas.append("  $idx = alloca i32\n")
        emit("store i32 0, ptr $idx")
        val lc = label("ainit.cond"); val lb = label("ainit.body"); val le = label("ainit.end")
        startBlock(lc)
        val i = emitT("load i32, ptr $idx")
        condBr(emitT("icmp slt i32 $i, ${n.v}"), lb, le)
        startBlock(lb)
        scopes.add(Scope())
        val pl = declare(pn, Ty.I32, false, false)
        emit("store i32 $i, ptr ${pl.slot}")
        val v = coerce(genBlockBody(lam, true), et)
        scopes.removeAt(scopes.size - 1)
        put(c, i, v)
        emit("store i32 ${emitT("add i32 $i, 1")}, ptr $idx")
        br(lc)
        startBlock(le)
        return c
    }

    /** Binds already computed values to fresh hidden locals for the duration of [f] (owned references are released after). */
    private fun withTemps(vals: List<Val>, f: (List<E>) -> Val): Val {
        scopes.add(Scope())
        val names = vals.map { v ->
            val n = "\$tmp${tmpN++}"
            val l = declare(n, v.t, false, v.owned && v.t.isRef)
            emit("store ${v.t.llvm} ${v.v}, ptr ${l.slot}")
            NameE(n).also { it.line = line } as E
        }
        val r = consume(f(names))
        releaseScope(scopes.last()); scopes.removeAt(scopes.size - 1)
        return r
    }

    /** Element kind (I, L, C) of a range built from [a] and [b]. */
    private fun rangeKind(a: Ty, b: Ty): String = when {
        a == Ty.Char && b == Ty.Char -> "C"
        a.isUnsigned || b.isUnsigned -> if (a == Ty.U64 || b == Ty.U64 || a == Ty.I64 || b == Ty.I64) "UL" else "U"
        a == Ty.I64 || b == Ty.I64 -> "L"
        a.isInt && b.isInt -> "I"
        else -> fail("cannot build a range of $a and $b")
    }

    private fun progKind(t: Ty): String? = (t as? Ty.Cls)?.let { PROGRESSIONS[it.name] }

    /** `a..b`, `a until b`, `a downTo b`, `p step n` as values: progression objects from the prelude. */
    private fun rangeValue(e: BinE): Val {
        val a = genExpr(e.l); val b = genExpr(e.r)
        val k = if (e.op == "step") progKind(a.t) ?: fail("step on ${a.t}") else rangeKind(a.t, b.t)
        val fn = "kt_" + mapOf(".." to "rangeTo", "until" to "until", "downTo" to "downTo", "step" to "step").getValue(e.op) + k
        return withTemps(listOf(a, b)) { (x, y) -> genNamedCall(fn, CallE(NameE(fn), emptyList(), listOf(x, y), null)) }
    }

    /** `x in r`: range literals compare against their bounds directly; other values call `contains`. */
    private fun inRange(xe: E, re: E): Val {
        if (re is BinE && re.op in setOf("..", "until", "downTo")) {
            val x = genExpr(xe); val lo = genExpr(re.l); val hi = genExpr(re.r)
            val k = rangeKind(lo.t, hi.t)
            val t0 = when (k) { "C" -> Ty.I32; "L" -> Ty.I64; "U" -> Ty.U32; "UL" -> Ty.U64; else -> if (x.t == Ty.I64) Ty.I64 else Ty.I32 }
            // A wider value is compared in its own type (`x: ULong in UInt.MIN_VALUE..UInt.MAX_VALUE`).
            val t = if (k != "C" && x.t.isInt && x.t.rank > t0.rank && x.t.isUnsigned == t0.isUnsigned) x.t else t0
            val c = { v: Val -> if (v.t == Ty.Char) Val(v.v, Ty.I32) else coerce(v, t) }
            if (k == "C" && x.t != Ty.Char) fail("type mismatch: expected Char, found ${x.t}")
            val xv = c(x); val l = c(lo); val h = c(hi)
            val (p, q) = when (re.op) {
                ".." -> compare("<=", l, xv) to compare("<=", xv, h)
                "until" -> compare("<=", l, xv) to compare("<", xv, h)
                else -> compare("<=", h, xv) to compare("<=", xv, l)
            }
            return Val(emitT("and i1 ${p.v}, ${q.v}"), Ty.I1)
        }
        val r = genExpr(re)
        return withTemps(listOf(r)) { (rv) -> genExpr(CallE(MemberE(rv, "contains", false).also { it.line = line }, emptyList(), listOf(xe), null).also { it.line = line }) }
    }

    private fun charOp(op: String, a: Val, b: Val): Val {
        val asInt = { v: Val -> if (v.t == Ty.Char) Val(v.v, Ty.I32) else v }
        return when {
            op in setOf("==", "!=", "<", ">", "<=", ">=") && a.t == Ty.Char && b.t == Ty.Char -> compare(op, asInt(a), asInt(b))
            op == "-" && a.t == Ty.Char && b.t == Ty.Char -> arith("-", asInt(a), asInt(b))
            // Char arithmetic wraps modulo 2^16.
            (op == "+" || op == "-") && a.t == Ty.Char && b.t.isInt -> Val(emitT("and i32 ${arith(op, asInt(a), coerce(b, Ty.I32)).v}, 65535"), Ty.Char)
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
        return when (r) { 2 -> if (u) Ty.U32 else Ty.I32; 3 -> if (u) Ty.U64 else Ty.I64; 4 -> Ty.F32; else -> Ty.F64 }
    }

    /** A nullable primitive operand of arithmetic/ordering: Kotlin only allows it smart-cast, so a checked unbox. */
    private fun nbArg(v: Val): Val = if (v.t is Ty.NBox) coerce(v, v.t.inner) else v

    private fun arith(op: String, a0: Val, b0: Val): Val {
        val a = nbArg(a0); val b = nbArg(b0)
        val t = numType(a, b)
        val x = coerce(a, t).v; val y = coerce(b, t).v
        val ins = if (t.isFp) mapOf("+" to "fadd", "-" to "fsub", "*" to "fmul", "/" to "fdiv", "%" to "frem")
        else if (t.isUnsigned) mapOf("+" to "add", "-" to "sub", "*" to "mul", "/" to "udiv", "%" to "urem")
        else mapOf("+" to "add", "-" to "sub", "*" to "mul", "/" to "sdiv", "%" to "srem")
        if (excMode && !t.isFp && (op == "/" || op == "%") && !(y.toLongOrNull()?.let { it != 0L } ?: false)) {
            val bad = label("div.zero"); val ok = label("div.ok")
            emit("br i1 ${emitT("icmp eq ${t.llvm} $y, 0")}, label %$bad, label %$ok, !prof !1"); term = true
            startBlock(bad)
            throwNew("ArithmeticException", Val(cstr("/ by zero"), Ty.Str))
            startBlock(ok)
        }
        return Val(emitT("${ins.getValue(op)} ${t.llvm} $x, $y"), t)
    }

    private val NUMERIC = setOf(Ty.I8, Ty.I32, Ty.I64, Ty.U32, Ty.U64, Ty.F32, Ty.F64, Ty.Char)
    private val CONST_RE = Regex("-?[0-9][0-9.eE+-]*|0x[0-9a-fA-F]+|true|false")
    private fun compare(op: String, a: Val, b: Val): Val {
        // Values of different primitive types are never equal (`equals` of boxed values). Kotlin rejects this for
        // non-literal operands outside generic code, so it only shows up through type parameters (`fun <A, B: Any> f(a: A, b: B) = a == b`).
        if ((op == "==" || op == "!=") && tenv.isNotEmpty() && a.t != b.t && a.t in NUMERIC && b.t in NUMERIC && !CONST_RE.matches(a.v) && !CONST_RE.matches(b.v))
            return Val(if (op == "==") "false" else "true", Ty.I1)
        // A type parameter bound by a `null` argument (RawPtr) against a primitive: null is never equal to a number.
        if ((op == "==" || op == "!=") && tenv.isNotEmpty() && ((a.t == Ty.RawPtr && b.t in NUMERIC) || (b.t == Ty.RawPtr && a.t in NUMERIC))) {
            releaseIfOwned(a); releaseIfOwned(b)
            return Val(if (op == "==") "false" else "true", Ty.I1)
        }
        if ((op == "==" || op == "!=") && a.v != "null" && b.v != "null" && listOf(a.t, b.t).any { it is Ty.Iface && it.name.startsWith("\$Fn") })
            fail("equality of function values is not supported")
        if ((op == "==" || op == "!=") && a.t is Ty.Cls && a.v != "null" && b.v != "null" && classes.getValue(a.t.name).methods["equals"]?.params?.size == 1) {
            // User-defined equals(other: Any?): same pointer -> true, null receiver -> other is null, else a.equals(b).
            val ls = label("ueq.same"); val lc = label("ueq.call"); val ln = label("ueq.null"); val le = label("ueq.end")
            val bb = if (b.t is Ty.Cls || b.t is Ty.Iface) b else coerce(b, Ty.Iface("Any"))
            condBr(emitT("icmp eq ptr ${a.v}, ${bb.v}"), ls, ln)
            startBlock(ls); br(le)
            startBlock(ln)
            val fromN = cur
            val lc2 = label("ueq.nn")
            condBr(emitT("icmp eq ptr ${a.v}, null"), le, lc2)
            startBlock(lc2); br(lc)
            startBlock(lc)
            val r = coerce(withTemps(listOf(Val(a.v, a.t, false), Val(bb.v, bb.t, false))) { (x, y) ->
                genExpr(CallE(MemberE(x, "equals", false).also { it.line = line }, emptyList(), listOf(y), null).also { it.line = line })
            }, Ty.I1)
            val fromC = cur
            br(le)
            startBlock(le)
            val eq = emitT("phi i1 [ true, %$ls ], [ false, %$fromN ], [ ${r.v}, %$fromC ]")
            releaseIfOwned(a); releaseIfOwned(bb)
            return Val(if (op == "==") eq else emitT("xor i1 $eq, true"), Ty.I1)
        }
        if ((op == "==" || op == "!=") && (isAny(a.t) || isAny(b.t)) && a.v != "null" && b.v != "null") {
            val anyT = if (isAny(a.t)) a.t else b.t
            val x = coerce(a, anyT); val y = coerce(b, anyT)
            val same = emitT("icmp eq ptr ${x.v}, ${y.v}")
            val r = coerce(anyCall("anyEquals__", listOf(x, y)), Ty.I1)
            val eq = emitT("or i1 $same, ${r.v}")
            return Val(if (op == "==") eq else emitT("xor i1 $eq, true"), Ty.I1)
        }
        if ((op == "==" || op == "!=") && (a.t is Ty.Arr) != (b.t is Ty.Arr) && (a.t.isInt || b.t.isInt)) fail("cannot compare ${a.t} and ${b.t}")
        if (a.t == Ty.I1 && b.t == Ty.I1 && (op == "==" || op == "!=")) return Val(emitT("icmp ${if (op == "==") "eq" else "ne"} i1 ${a.v}, ${b.v}"), Ty.I1)
        // `null` vs a non-nullable primitive never matches (kotlinc only warns).
        if ((a.v == "null" && b.t.llvm != "ptr") || (b.v == "null" && a.t.llvm != "ptr")) {
            if (op != "==" && op != "!=") fail("cannot order null")
            return Val(if (op == "==") "false" else "true", Ty.I1)
        }
        if (a.t is Ty.Cls && a.t == b.t && a.v != "null" && b.v != "null" && (classDecls[a.t.name]?.isValue == true || isData(a.t)) && (op == "==" || op == "!=")) {
            // Value classes compare by their single property (null-safe: same pointer -> equal, one null -> different).
            val ci = classes.getValue(a.t.name); val ft = ci.fields[0].second
            val same = emitT("icmp eq ptr ${a.v}, ${b.v}")
            val anyNull = emitT("or i1 ${emitT("icmp eq ptr ${a.v}, null")}, ${emitT("icmp eq ptr ${b.v}, null")}")
            val from = cur
            val lf = label("veq.fields"); val le = label("veq.end"); val lc = label("veq.chk")
            condBr(same, le, lc)
            startBlock(lc)
            val fromC = cur
            condBr(anyNull, le, lf)
            startBlock(lf)
            val eq = if (isData(a.t)) coerce(withTemps(listOf(Val(a.v, a.t, false), Val(b.v, b.t, false))) { (x, y) ->
                genExpr(CallE(MemberE(x, "\$equals", false).also { it.line = line }, emptyList(), listOf(y), null).also { it.line = line })
            }, Ty.I1) else {
                val fa = Val(emitT("load ${ft.llvm}, ptr ${emitT("getelementptr ${ci.llType}, ptr ${a.v}, i32 0, i32 2")}"), ft)
                val fb = Val(emitT("load ${ft.llvm}, ptr ${emitT("getelementptr ${ci.llType}, ptr ${b.v}, i32 0, i32 2")}"), ft)
                // Value class equality is boxed equality of the property: Float/Double compare by bits.
                if (ft.isFp) compare("==", Val(doubleBits(fa), Ty.I64), Val(doubleBits(fb), Ty.I64)) else coerce(compare("==", fa, fb), Ty.I1)
            }
            val fromF = cur
            br(le)
            startBlock(le)
            val r = emitT("phi i1 [ true, %$from ], [ false, %$fromC ], [ ${eq.v}, %$fromF ]")
            releaseIfOwned(a); releaseIfOwned(b)
            return Val(if (op == "==") r else emitT("xor i1 $r, true"), Ty.I1)
        }
        if (a.t is Ty.ListTy && b.t is Ty.ListTy && (op == "==" || op == "!=")) {
            // Structural list equality: same size and pairwise-equal elements (null-safe).
            val res = "%l.leq.${tmpN++}"; val idx = "%l.leqi.${tmpN++}"
            allocas.append("  $res = alloca i1\n  $idx = alloca i64\n")
            emit("store i1 false, ptr $res"); emit("store i64 0, ptr $idx")
            val lt = label("leq.true"); val le = label("leq.end"); val ln = label("leq.nn"); val ls = label("leq.size")
            val lc = label("leq.cond"); val lb = label("leq.body")
            emit("store i1 true, ptr $res")
            condBr(emitT("icmp eq ptr ${a.v}, ${b.v}"), le, ln)
            startBlock(ln)
            emit("store i1 false, ptr $res")
            condBr(emitT("or i1 ${emitT("icmp eq ptr ${a.v}, null")}, ${emitT("icmp eq ptr ${b.v}, null")}"), le, ls)
            startBlock(ls)
            val n = emitT("call i64 @rt_list_size(ptr ${a.v})")
            condBr(emitT("icmp eq i64 $n, ${emitT("call i64 @rt_list_size(ptr ${b.v})")}"), lc, le)
            startBlock(lc)
            val i = emitT("load i64, ptr $idx")
            condBr(emitT("icmp slt i64 $i, $n"), lb, lt)
            startBlock(lb)
            val ea = Val(fromSlot(emitT("call i64 @rt_list_get(ptr ${a.v}, i64 $i)"), a.t.elem), a.t.elem)
            val eb = Val(fromSlot(emitT("call i64 @rt_list_get(ptr ${b.v}, i64 $i)"), b.t.elem), b.t.elem)
            val asInt = { v: Val -> if (v.t == Ty.Char) Val(v.v, Ty.I32) else v }
            val eq = compare("==", asInt(ea), asInt(eb))
            emit("store i64 ${emitT("add i64 $i, 1")}, ptr $idx")
            condBr(eq.v, lc, le)
            startBlock(lt)
            emit("store i1 true, ptr $res")
            br(le)
            startBlock(le)
            val r = emitT("load i1, ptr $res")
            releaseIfOwned(a); releaseIfOwned(b)
            return Val(if (op == "==") r else emitT("xor i1 $r, true"), Ty.I1)
        }
        if (a.t == Ty.Str && b.t == Ty.Str && op in setOf("<", ">", "<=", ">=")) {
            val c = emitT("call i32 @rt_str_cmp(ptr ${a.v}, ptr ${b.v})")
            releaseIfOwned(a); releaseIfOwned(b)
            return compare(op, Val(c, Ty.I32), Val("0", Ty.I32))
        }
        if (a.t is Ty.Cls && op in setOf("<", ">", "<=", ">=") && "compareTo" in classes.getValue(a.t.name).methods) {
            val c = withTemps(listOf(a, b)) { (x, y) -> genExpr(CallE(MemberE(x, "compareTo", false).also { it.line = line }, emptyList(), listOf(y), null).also { it.line = line }) }
            return compare(op, c, Val("0", Ty.I32))
        }
        if ((op == "==" || op == "!=") && (a.t is Ty.NBox || b.t is Ty.NBox) && a.v != "null" && b.v != "null" &&
            (a.t is Ty.NBox || boxName(a.t) != null) && (b.t is Ty.NBox || boxName(b.t) != null) && a.t != Ty.Str && b.t != Ty.Str) return nboxEq(op, a, b)
        // Ordering needs a non-null smart cast (handled by aliases) or a user `compareTo` on the nullable type.
        if (op != "==" && op != "!=" && (a.t is Ty.NBox || b.t is Ty.NBox)) fail("ordering of ${a.t} and ${b.t} is not supported")
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
        val res = if (t.isFp) {
            val c = mapOf("==" to "oeq", "!=" to "une", "<" to "olt", ">" to "ogt", "<=" to "ole", ">=" to "oge").getValue(op)
            emitT("fcmp $c ${t.llvm} $x, $y")
        } else {
            val c = if (t.isUnsigned) mapOf("==" to "eq", "!=" to "ne", "<" to "ult", ">" to "ugt", "<=" to "ule", ">=" to "uge").getValue(op)
            else mapOf("==" to "eq", "!=" to "ne", "<" to "slt", ">" to "sgt", "<=" to "sle", ">=" to "sge").getValue(op)
            emitT("icmp $c ${t.llvm} $x, $y")
        }
        return Val(res, Ty.I1)
    }
}

private val TB_TYS = listOf("i1", "i8", "i16", "i32", "i64", "float", "double", "ptr")

/** Scalar TBAA type tree rooted at "kx": length, elem.<ty>, field.<ty> are disjoint siblings. */
private fun tbaaMeta(): String = buildString {
    append("!20 = !{!\"kx tbaa\"}\n!19 = !{!\"kx.len\", !20, i64 0}\n!21 = !{!19, !19, i64 0}\n")
    TB_TYS.forEachIndexed { i, t ->
        append("!${70 + i} = !{!\"kx.elem.$t\", !20, i64 0}\n!${30 + i} = !{!${70 + i}, !${70 + i}, i64 0}\n")
        append("!${90 + i} = !{!\"kx.field.$t\", !20, i64 0}\n!${50 + i} = !{!${90 + i}, !${90 + i}, i64 0}\n")
    }
}

private val PRELUDE = """
target triple = "arm64-apple-macosx14.0.0"
@rt_argc = global i32 0
@rt_exc = internal global ptr null
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
declare void @rt_throw(ptr, ptr) noreturn
@rt_leakcheck = external global i32
declare void @rt_exit_flush()
declare void @rt_print_str(ptr)
declare void @rt_print_i64(i64)
declare void @rt_print_f64(double)
declare void @rt_print_f32(float)
declare void @rt_print_bool(i1 zeroext)
declare void @rt_newline()
declare ptr @rt_sb_new()
declare void @rt_sb_str(ptr, ptr)
declare void @rt_sb_i64(ptr, i64)
declare void @rt_sb_f64(ptr, double)
declare void @rt_sb_f32(ptr, float)
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
declare i32 @rt_str_hash(ptr)
declare i32 @rt_str_cmp(ptr, ptr)
declare i32 @rt_double_hash(double)
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
declare float @llvm.sqrt.f32(float)
declare i32 @llvm.fptosi.sat.i32.f32(float)
declare i32 @llvm.fptosi.sat.i32.f64(double)
declare i64 @llvm.fptosi.sat.i64.f32(float)
declare i64 @llvm.fptosi.sat.i64.f64(double)
declare i32 @llvm.fptoui.sat.i32.f32(float)
declare i32 @llvm.fptoui.sat.i32.f64(double)
declare i64 @llvm.fptoui.sat.i64.f32(float)
declare i64 @llvm.fptoui.sat.i64.f64(double)
declare float @llvm.fabs.f32(float)
!0 = !{!"branch_weights", i32 2000, i32 1}
!1 = !{!"branch_weights", i32 1, i32 2000}
${tbaaMeta()}
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

/** Lambda-with-receiver bodies with `this` added as first parameter; kept across code-generation restarts (stable ids). */
private object RecvLambdas { val map = HashMap<Block, Block>() }
