package newkotlin

// Coroutine lowering: suspend function bodies as LLVM coroutines, suspension points and `await`.
// Members of [NativeBackend], split out by concern (extension functions on the backend instance).

/**
 * A suspend function as an LLVM switched-resume coroutine (CPS state machine): `ptr @f(params..., ptr %co.ret)`
 * returns its handle after an initial suspension; LLVM's CoroSplit turns it into resume/destroy functions and
 * spills locals and values live across suspension points into the heap frame. A `ret` of the body stores the
 * result into `%co.ret` (caller's slot, null for Unit) and reaches the final suspension.
 */
internal fun NativeBackend.genCoroutine(name: String, params: List<String>, isMain: Boolean) {
    if (isMain) fail("suspend fun main is not supported; use fun main() = runBlocking { ... }")
    val out = StringBuilder()
    for (ln in body.lines()) {
        if (ln == "  ret void") { out.append("  br label %co.final\n"); continue }
        if (ln.startsWith("  ret ")) {
            val rest = ln.removePrefix("  ret ")
            val sp = rest.indexOf(' ')
            out.append("  store ${rest.substring(0, sp)} ${rest.substring(sp + 1)}, ptr %co.ret\n  br label %co.final\n"); continue
        }
        out.append(ln).append('\n')
    }
    funcs.append("define internal ptr @$name(${(params + "ptr %co.ret").joinToString(", ")}) presplitcoroutine nounwind {\nco.pro:\n")
    // Allocas first; their initializing stores run after coro.begin (the frame may hold them).
    val al = allocas.lines().filter { it.isNotBlank() }
    for (ln in al) if (" = alloca " in ln) funcs.append(ln).append('\n')
    funcs.append("  %co.id = call token @llvm.coro.id(i32 0, ptr null, ptr null, ptr null)\n")
    funcs.append("  %co.sz = call ${llvmTarget.cSize} @llvm.coro.size.${llvmTarget.cSize}()\n  %co.mem = call ptr @malloc(${llvmTarget.cSize} %co.sz)\n")
    funcs.append("  %co.hdl = call ptr @llvm.coro.begin(token %co.id, ptr %co.mem)\n")
    for (ln in al) if (" = alloca " !in ln) funcs.append(ln).append('\n')
    funcs.append("  %co.s0 = call i8 @llvm.coro.suspend(token none, i1 false)\n")
    funcs.append("  switch i8 %co.s0, label %co.susp [ i8 0, label %entry  i8 1, label %co.clean ]\nentry:\n")
    funcs.append(out)
    funcs.append("co.final:\n  %co.sf = call i8 @llvm.coro.suspend(token none, i1 true)\n")
    funcs.append("  switch i8 %co.sf, label %co.susp [ i8 0, label %co.clean  i8 1, label %co.clean ]\n")
    funcs.append("co.clean:\n  %co.fm = call ptr @llvm.coro.free(token %co.id, ptr %co.hdl)\n  call void @free(ptr %co.fm)\n  br label %co.susp\n")
    funcs.append("co.susp:\n  %co.e = call i1 @llvm.coro.end(ptr %co.hdl, i1 false, token none)\n  ret ptr %co.hdl\n}\n\n")
}

/** A suspension point of the current coroutine: control returns to whoever resumed it. */
internal fun NativeBackend.coSuspend() {
    if (!inCoroutine) fail("suspension point outside a coroutine")
    val cont = label("co.cont")
    val s0 = emitT("call i8 @llvm.coro.suspend(token none, i1 false)")
    emit("switch i8 $s0, label %co.susp [ i8 0, label %$cont  i8 1, label %co.clean ]"); term = true
    startBlock(cont)
}

/**
 * Call of a suspend function from a coroutine: create the callee's coroutine, then resume it until it is done,
 * suspending this coroutine each time the callee suspends (a resume of this one resumes the callee first).
 */
internal fun NativeBackend.awaitCall(call: String, ret: Ty): Val {
    if (!inCoroutine) fail("suspend function called outside a coroutine (call it from a suspend function or a suspend lambda)")
    val unit = ret == Ty.Unit || ret == Ty.Nothing
    val slot = if (unit) "null" else "%co.slot${tmpN++}".also { allocas.append("  $it = alloca ${ret.llvm}\n") }
    val head = "call ${ret.llvm} "
    if (!call.startsWith(head) || !call.endsWith(")")) fail("internal: unexpected suspend call shape")
    val inner = call.removePrefix(head).dropLast(1)
    val h = emitT("call ptr $inner${if (inner.endsWith("(")) "" else ", "}ptr $slot)")
    val loop = label("co.aw"); val done = label("co.awd"); val wait = label("co.aws")
    br(loop); startBlock(loop)
    emit("call void @llvm.coro.resume(ptr $h)")
    condBr(emitT("call i1 @llvm.coro.done(ptr $h)"), done, wait)
    startBlock(wait)
    val s0 = emitT("call i8 @llvm.coro.suspend(token none, i1 false)")
    emit("switch i8 $s0, label %co.susp [ i8 0, label %$loop  i8 1, label %co.clean ]"); term = true
    startBlock(done)
    emit("call void @llvm.coro.destroy(ptr $h)")
    return if (unit) Val("", Ty.Unit) else Val(emitT("load ${ret.llvm}, ptr $slot"), ret, ret.isRef)
}

/** A plain function value where a suspend function type is expected (`(A) -> R` to `suspend (A) -> R`). */
internal fun NativeBackend.isSuspendConversion(a: Ty, b: Ty): Boolean = a is Ty.Iface && b is Ty.Iface &&
    ((a.name.startsWith("\$Fn$") && b.name == "\$FnS$" + a.name.removePrefix("\$Fn$")) ||
     (a.name.startsWith("\$FnR$") && b.name == "\$FnRS$" + a.name.removePrefix("\$FnR$")))

/** Per function and builder: the line and type bindings of the last builder call whose type was inferred. */
/**
 * The common type of alternative values ([tails]: `if`/`when` branch results, vararg arguments) when only some of
 * them determine it (`if (c) build { set(x) } else build {}`, `else -> Buildee()`): each is probed on its own; the
 * ones that cannot be typed alone are left out, and a single type among the others is the result. Only computed
 * when some tail is a call of a generic function or class (the others are typed alone anyway).
 */
internal fun NativeBackend.commonType(tails: List<E>): Ty? {
    fun generic(x: E) = x is CallE && (x.callee as? NameE)?.n?.let { it in genericFuns || it in genericClasses } == true
    if (tails.size < 2 || tails.none { generic(it) }) return null
    val ts = tails.map { x -> try { probeType(x) } catch (c: CompileError) { null } }
    if (ts.none { it == null }) return null
    val known = ts.filterNotNull().filter { (it is Ty.Cls || it is Ty.Iface) && fnSig(it) == null }.distinct()  // function types: left to the old path (kt35914)
    return known.singleOrNull()
}

internal fun NativeBackend.tailOf(b: Block?): E? = (b?.stmts?.lastOrNull() as? ExprS)?.e

/** Wraps [v] in a suspend lambda forwarding its arguments (the value is held in a scope local). */
internal fun NativeBackend.suspendConvert(v: Val, t: Ty.Iface): Val {
    val sig = fnSig(v.t)!!
    val tmp = "\$sconv${suspConvN++}"
    val l = declare(tmp, v.t, false, true)
    emit("store ptr ${consume(v).v}, ptr ${l.slot}")
    val ps = sig.params.indices.map { "\$sp$it" }
    val call = CallE(NameE(tmp, line = line), emptyList(), ps.map { NameE(it, line = line) }, null, line = line)
    // Cached per function and site: a restart of the function's generation must reuse the same lambda class.
    val lam = SuspConvLambdas.map.getOrPut("$curFunKey|$tmp|${v.t}|$t") { LambdaE(Block(listOf(ExprS(call, line = line)), ps, emptyList()), line = line) }
    val r = genLambda(lam, sig.params, sig.ret, false, susp = true)
    return if (r.t == t) r else Val(r.v, t, r.owned)
}
