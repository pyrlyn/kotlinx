package newkotlin

/*
 * kotlinx libraries written in Kotlin and merged into a program that uses them (like Prelude.kt):
 *
 * - kotlinx.coroutines subset: `runBlocking`, `coroutineScope`, `launch`, `async`/`await`, `Job` (join, cancel,
 *   isActive/isCompleted), `joinAll`, `awaitAll`, `yield`, `delay`, `Channel` (send/receive/trySend/close/consumeEach)
 *   and cold `Flow` (`flow { emit(x) }`, `flowOf`, `map`, `filter`, `collect`, `toList`). Coroutines are CPS:
 *   suspend functions and suspend lambdas are LLVM coroutines (NativeBackend.genCoroutine) driven by a
 *   single-threaded scheduler: `join`, `await`, `receive`, `send` (full or rendezvous channel), `yield` and
 *   `delay` really suspend the calling coroutine. `delay` uses virtual time (timers ordered by wake-up time).
 *   `runBlocking`/`coroutineScope` return Unit (generic `T = Unit` parameters are not supported yet).
 * - kotlinx.serialization subset: `@Serializable` classes get a generated `KSerializer` (`X.serializer()`), `Json`
 *   with `encodeToString`/`decodeFromString(serializer, ...)`, `parseToJsonElement`, and the `JsonElement` tree
 *   (`JsonObject`, `JsonArray`, `JsonPrimitive`, `JsonNull`). Supported property types: Int, Long, Double, Boolean,
 *   String, other @Serializable classes, List<T> of those, and nullable variants.
 */


val KOTLINX_SERIALIZATION = """
class SerializationException(message: String) : IllegalArgumentException(message)
interface JsonElement { fun render(sb: StringBuilder); override fun toString(): String }
fun kxJsonQuote(s: String, sb: StringBuilder) {
    sb.append('"')
    for (c in s) {
        if (c == '"') sb.append("\\\"") else if (c == '\\') sb.append("\\\\") else if (c == '\n') sb.append("\\n")
        else if (c == '\t') sb.append("\\t") else if (c == '\r') sb.append("\\r") else sb.append(c)
    }
    sb.append('"')
}
fun kxParseLong(s: String): Long {
    var i = 0; var neg = false
    if (s.length > 0 && s[0] == '-') { neg = true; i = 1 }
    if (i >= s.length) throw SerializationException("not a number: " + s)
    var v = 0L
    while (i < s.length) {
        val c = s[i]
        if (c < '0' || c > '9') throw SerializationException("not an integer: " + s)
        v = v * 10L + (c - '0').toLong(); i++
    }
    return if (neg) 0L - v else v
}
fun kxParseDouble(s: String): Double {
    var i = 0; var neg = false
    if (s.length > 0 && s[0] == '-') { neg = true; i = 1 }
    var v = 0.0; var digits = 0
    while (i < s.length && s[i] >= '0' && s[i] <= '9') { v = v * 10.0 + (s[i] - '0').toDouble(); i++; digits++ }
    if (i < s.length && s[i] == '.') {
        i++
        var scale = 0.1
        while (i < s.length && s[i] >= '0' && s[i] <= '9') { v += (s[i] - '0').toDouble() * scale; scale = scale / 10.0; i++; digits++ }
    }
    if (digits == 0) throw SerializationException("not a number: " + s)
    if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
        i++
        var eneg = false
        if (i < s.length && (s[i] == '-' || s[i] == '+')) { eneg = s[i] == '-'; i++ }
        var e = 0
        while (i < s.length && s[i] >= '0' && s[i] <= '9') { e = e * 10 + (s[i] - '0'); i++ }
        for (k in 0 until e) v = if (eneg) v / 10.0 else v * 10.0
    }
    if (i != s.length) throw SerializationException("not a number: " + s)
    return if (neg) 0.0 - v else v
}
class JsonPrimitive(val content: String, val isString: Boolean) : JsonElement {
    override fun render(sb: StringBuilder) { if (isString) { kxJsonQuote(content, sb); return }; sb.append(content) }
    override fun toString(): String { val sb = StringBuilder(); render(sb); return sb.toString() }
    val isNull: Boolean get() = !isString && content == "null"
    val int: Int get() { val v = kxParseLong(content); if (v < -2147483648L || v > 2147483647L) throw SerializationException("not an Int: " + content); return v.toInt() }
    val long: Long get() = kxParseLong(content)
    val double: Double get() = kxParseDouble(content)
    val boolean: Boolean get() = if (content == "true") true else if (content == "false") false else throw SerializationException("not a boolean: " + content)
}
val JsonNull: JsonPrimitive = JsonPrimitive("null", false)
class JsonArray(val items: List<JsonElement>) : JsonElement {
    val size: Int get() = items.size
    fun get(i: Int): JsonElement = items[i]
    override fun render(sb: StringBuilder) {
        sb.append('[')
        for (i in 0 until items.size) { if (i > 0) sb.append(','); items[i].render(sb) }
        sb.append(']')
    }
    override fun toString(): String { val sb = StringBuilder(); render(sb); return sb.toString() }
}
class JsonObject(val keys: List<String>, val values: List<JsonElement>) : JsonElement {
    val size: Int get() = keys.size
    fun get(key: String): JsonElement? {
        for (i in 0 until keys.size) if (keys[i] == key) return values[i]
        return null
    }
    fun containsKey(key: String): Boolean = get(key) != null
    override fun render(sb: StringBuilder) {
        sb.append('{')
        for (i in 0 until keys.size) { if (i > 0) sb.append(','); kxJsonQuote(keys[i], sb); sb.append(':'); values[i].render(sb) }
        sb.append('}')
    }
    override fun toString(): String { val sb = StringBuilder(); render(sb); return sb.toString() }
}
class JsonObjectBuilder {
    val keys = ArrayList<String>()
    val values = ArrayList<JsonElement>()
    fun put(key: String, v: JsonElement) { keys.add(key); values.add(v) }
    fun build(): JsonObject = JsonObject(keys, values)
}
class KxJsonParser(val s: String) {
    var i = 0
    fun fail(msg: String): SerializationException = SerializationException("Unexpected JSON token at offset " + i + ": " + msg)
    fun ws() { while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\t' || s[i] == '\r')) i++ }
    fun expect(c: Char) { ws(); if (i >= s.length || s[i] != c) throw fail("expected '" + c + "'"); i++ }
    fun str(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            if (i >= s.length) throw fail("unterminated string")
            val c = s[i]; i++
            if (c == '"') break
            if (c == '\\') {
                if (i >= s.length) throw fail("bad escape")
                val e = s[i]; i++
                if (e == 'n') sb.append('\n') else if (e == 't') sb.append('\t') else if (e == 'r') sb.append('\r')
                else if (e == 'u') {
                    if (i + 4 > s.length) throw fail("bad unicode escape")
                    var code = 0
                    for (k in 0 until 4) {
                        val h = s[i + k]
                        val d = if (h >= '0' && h <= '9') h - '0' else if (h >= 'a' && h <= 'f') h - 'a' + 10 else if (h >= 'A' && h <= 'F') h - 'A' + 10 else throw fail("bad hex digit")
                        code = code * 16 + d
                    }
                    i += 4
                    sb.append(code.toChar())
                } else sb.append(e)
            } else sb.append(c)
        }
        return sb.toString()
    }
    fun value(): JsonElement {
        ws()
        if (i >= s.length) throw fail("unexpected end of input")
        val c = s[i]
        if (c == '{') {
            i++
            val b = JsonObjectBuilder()
            ws()
            if (i < s.length && s[i] == '}') { i++; return b.build() }
            while (true) {
                val k = str(); expect(':'); b.put(k, value()); ws()
                if (i < s.length && s[i] == ',') { i++; continue }
                expect('}'); return b.build()
            }
        }
        if (c == '[') {
            i++
            val xs = ArrayList<JsonElement>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return JsonArray(xs) }
            while (true) {
                xs.add(value()); ws()
                if (i < s.length && s[i] == ',') { i++; continue }
                expect(']'); return JsonArray(xs)
            }
        }
        if (c == '"') return JsonPrimitive(str(), true)
        val lb = StringBuilder()
        while (i < s.length && (s[i] == '-' || s[i] == '+' || s[i] == '.' || (s[i] >= '0' && s[i] <= '9') || (s[i] >= 'a' && s[i] <= 'z') || s[i] == 'E')) { lb.append(s[i]); i++ }
        if (lb.length == 0) throw fail("unexpected character '" + c + "'")
        val lit = lb.toString()
        if (lit == "true" || lit == "false" || lit == "null") return JsonPrimitive(lit, false)
        if (lit[0] == '-' || (lit[0] >= '0' && lit[0] <= '9')) return JsonPrimitive(lit, false)
        throw fail("unexpected literal '" + lit + "'")
    }
    fun parse(): JsonElement { val v = value(); ws(); if (i != s.length) throw fail("trailing input"); return v }
}
interface KSerializer<T> {
    fun serialize(value: T): JsonElement
    fun deserialize(element: JsonElement): T
}
object Json {
    fun parseToJsonElement(s: String): JsonElement = KxJsonParser(s).parse()
    fun <T> encodeToString(serializer: KSerializer<T>, value: T): String = serializer.serialize(value).toString()
    fun <T> decodeFromString(serializer: KSerializer<T>, s: String): T { val e = KxJsonParser(s).parse(); return serializer.deserialize(e) }
    fun <T> encodeToJsonElement(serializer: KSerializer<T>, value: T): JsonElement = serializer.serialize(value)
    fun <T> decodeFromJsonElement(serializer: KSerializer<T>, e: JsonElement): T = serializer.deserialize(e)
}
fun kxPrim(e: JsonElement?, what: String): JsonPrimitive {
    if (e == null) throw SerializationException("Field '" + what + "' is required, but it was missing")
    if (e is JsonPrimitive) return e
    throw SerializationException("Field '" + what + "': expected a JSON primitive")
}
fun kxObj(e: JsonElement, what: String): JsonObject {
    if (e is JsonObject) return e
    throw SerializationException("Expected a JSON object for " + what)
}
fun kxReq(e: JsonElement?, what: String): JsonElement {
    if (e == null) throw SerializationException("Field '" + what + "' is required, but it was missing")
    return e
}
fun kxArr(e: JsonElement?, what: String): JsonArray {
    if (e == null) throw SerializationException("Field '" + what + "' is required, but it was missing")
    if (e is JsonArray) return e
    throw SerializationException("Field '" + what + "': expected a JSON array")
}
"""

val COROUTINES_USE = Regex("""\brunBlocking\b|\bcoroutineScope\b|\bChannel<|\bflow(Of)?\s*[<{(]|\bsuspend\b|\bkotlin\.coroutines\b|\bContinuation\b|\bsupervisorScope\b|\bCoroutineExceptionHandler\b""")
val SERIALIZATION_USE = Regex("""@Serializable|\bJson\.|\bKSerializer\b|\bJson(Element|Object|Array|Primitive|Null)\b""")
private val SERIALIZABLE_CLASS = Regex("""@Serializable\s+(?:data\s+)?class\s+(\w+)""")

/**
 * The kotlinx.serialization plugin: for every `@Serializable class X` appends `class X_KxSerializer : KSerializer<X>`
 * with a singleton `X_kxSerializer`
 * (JSON object with the primary-constructor properties in order) and rewrites `X.serializer()` to the singleton. Works on source
 * text so line numbers of user code are unchanged.
 */
fun withSerializable(src: String): String {
    val names = SERIALIZABLE_CLASS.findAll(src).map { it.groupValues[1] }.toList()
    if (names.isEmpty()) return src
    val prog = Parser(Lexer(src).lex()).program()
    val gen = StringBuilder()
    var n = 0
    fun ty(t: TypeRef): String = t.name + (if (t.args.isEmpty()) "" else t.args.joinToString(", ", "<", ">") { ty(it) }) + (if (t.nullable) "?" else "")
    fun nonNull(t: TypeRef) = TypeRef(t.name, t.args, false)
    fun enc(t: TypeRef, v: String): String {
        if (t.nullable) {
            val f = "kxEnc_${n++}"
            gen.append("fun $f(v: ${ty(t)}): JsonElement { if (v == null) return JsonNull; return ${enc(nonNull(t), "v!!")} }\n")
            return "$f($v)"
        }
        return when (t.name) {
            "Int", "Long", "Double", "Boolean" -> "JsonPrimitive(\"\" + $v, false)"
            "String" -> "JsonPrimitive($v, true)"
            "List" -> {
                val f = "kxEnc_${n++}"
                gen.append("fun $f(v: ${ty(t)}): JsonElement { val r = ArrayList<JsonElement>(); for (x in v) r.add(${enc(t.args.single(), "x")}); return JsonArray(r) }\n")
                "$f($v)"
            }
            in names -> "${t.name}_kxSerializer.serialize($v)"
            else -> throw CompileError("@Serializable: unsupported property type ${ty(t)}")
        }
    }
    fun dec(t: TypeRef, e: String, what: String): String {
        if (t.nullable) {
            val f = "kxDec_${n++}"
            gen.append("fun $f(e: JsonElement?): ${ty(t)} { if (e == null) return null; if (e is JsonPrimitive && e.isNull) return null; return ${dec(nonNull(t), "e", what)} }\n")
            return "$f($e)"
        }
        return when (t.name) {
            "Int" -> "kxPrim($e, \"$what\").int"
            "Long" -> "kxPrim($e, \"$what\").long"
            "Double" -> "kxPrim($e, \"$what\").double"
            "Boolean" -> "kxPrim($e, \"$what\").boolean"
            "String" -> "kxPrim($e, \"$what\").content"
            "List" -> {
                val f = "kxDec_${n++}"
                val el = t.args.single()
                gen.append("fun $f(a: JsonArray): ArrayList<${ty(el)}> { val r = ArrayList<${ty(el)}>(); for (i in 0 until a.size) r.add(${dec(el, "a.get(i)", what)}); return r }\n")
                "$f(kxArr($e, \"$what\"))"
            }
            in names -> {
                val f = "kxDec_${n++}"
                gen.append("fun $f(e: JsonElement?): ${t.name} { val r = kxReq(e, \"$what\"); return ${t.name}_kxSerializer.deserialize(r) }\n")
                "$f($e)"
            }
            else -> throw CompileError("@Serializable: unsupported property type ${ty(t)}")
        }
    }
    var out = src
    for (name in names) {
        val c = prog.classes.firstOrNull { it.name == name } ?: throw CompileError("@Serializable class $name not found")
        val ps = c.ctorParams
        val encs = ps.joinToString("") { "b.put(\"${it.name}\", ${enc(it.type, "value." + it.name)}); " }
        // Decoded into locals first: a throwing decoder inside a constructor argument list would leak the
        // already evaluated arguments.
        val decs = ps.withIndex().joinToString("") { (i, p) -> "val p$i = ${dec(p.type, "o.get(\"${p.name}\")", p.name)}; " }
        val args = ps.indices.joinToString(", ") { "p$it" }
        gen.append("class ${name}_KxSerializer : KSerializer<$name> {\n" +
            "    override fun serialize(value: $name): JsonElement { val b = JsonObjectBuilder(); $encs; return b.build() }\n" +
            "    override fun deserialize(element: JsonElement): $name { val o = kxObj(element, \"$name\"); $decs return $name($args) }\n}\n")
        out = out.replace(Regex("""\b$name\.serializer\(\)"""), "${name}_kxSerializer")
        gen.append("val ${name}_kxSerializer: KSerializer<$name> = ${name}_KxSerializer()\n")
    }
    return out + "\n" + gen
}

/**
 * kotlinx.coroutines on CPS: every suspend function and suspend lambda is an LLVM coroutine (state machine with a
 * heap frame, NativeBackend.genCoroutine); `kxCoSuspend()` is the primitive suspension point, `kxCoStart` /
 * `kxCoResume` / `kxCoDestroy` drive a job's root coroutine from the single-threaded scheduler.
 */
val KOTLINX_COROUTINES = """
class CancellationException(message: String) : IllegalStateException(message)
class ClosedReceiveChannelException(message: String) : IllegalStateException(message)
object KxSched {
    val ready = ArrayList<Job>()
    val timers = ArrayList<Job>()
    // Started, unfinished jobs: cancelled and drained when runBlocking cannot make progress.
    val live = ArrayList<Job>()
    var current: Job? = null
    var now = 0L
    fun wake(j: Job) { ready.add(j) }
    fun wakeAll(ws: ArrayList<Job>) {
        for (w in ws) ready.add(w)
        while (ws.size > 0) ws.removeAt(ws.size - 1)
    }
    fun addTimer(j: Job) {
        timers.add(j)
        var i = timers.size - 1
        while (i > 0 && timers[i - 1].wakeAt > j.wakeAt) { val t = timers[i - 1]; timers[i - 1] = timers[i]; timers[i] = t; i-- }
    }
    fun dropTimer(j: Job) {
        var i = 0
        while (i < timers.size) { if (timers[i] === j) { val t = timers.removeAt(i) } else i++ }
    }
    fun dropLive(j: Job) {
        var i = 0
        while (i < live.size) { if (live[i] === j) { val t = live.removeAt(i) } else i++ }
    }
    fun isReady(j: Job): Boolean { for (r in ready) if (r === j) return true; return false }
    // Resumes a suspended job so its suspension point can observe a cancellation.
    fun poke(j: Job) {
        if (current === j || j.isCompleted) return
        dropTimer(j)
        if (!isReady(j)) wake(j)
    }
    // A nested runBlocking runs this loop inside a running job; that job is parked until the inner loop returns.
    // On a deadlock every live job is cancelled (so suspended coroutines unwind and free their frames), then it throws.
    fun runUntil(root: Job) {
        val parked = ArrayList<Job>()
        var deadlock = false
        while (!root.isCompleted) {
            if (ready.size > 0) { val j = ready.removeAt(0); if (j.running) parked.add(j) else j.step() }
            else if (timers.size > 0) { val j = timers.removeAt(0); if (j.wakeAt > now) now = j.wakeAt; if (j.running) parked.add(j) else j.step() }
            else if (!deadlock) {
                deadlock = true
                var i = 0
                while (i < live.size) { val j = live[i]; if (!j.running) j.cancel(); i++ }
                root.cancel()
            }
            else throw IllegalStateException("runBlocking: coroutines cannot be cancelled")
        }
        for (p in parked) ready.add(p)
        if (deadlock) throw IllegalStateException("runBlocking: deadlock, no coroutine can make progress")
    }
    // After the outermost runBlocking: detached jobs still pending are cancelled and unwound (frames freed).
    fun drainDetached() {
        var rounds = 0
        while (live.size > 0 && rounds < 1000) {
            var i = 0
            while (i < live.size) { val j = live[i]; if (!j.running) j.cancel(); i++ }
            while (ready.size > 0) ready.removeAt(0).step()
            while (timers.size > 0) timers.removeAt(0).step()
            rounds++
        }
    }
    fun me(): Job {
        val c = current
        if (c == null) throw IllegalStateException("suspend function called outside a coroutine")
        return c
    }
}
// Every suspension goes through here: a cancelled job (or one inside a failed scope) resumes with CancellationException.
suspend fun kxSuspend() {
    kxCoSuspend()
    val j = KxSched.me()
    if (j.cancelling()) throw CancellationException("Job was cancelled")
}
open class Job(body0: suspend () -> Unit) {
    var body: suspend () -> Unit = body0
    var handle = 0L
    var started = false
    var running = false
    var isCompleted = false
    var isCancelled = false
    var wakeAt = 0L
    var scope: CoroutineScope? = null
    // Scopes this job is executing (its own, then nested coroutineScope blocks).
    val scopes = ArrayList<CoroutineScope>()
    val waiters = ArrayList<Job>()
    val isActive: Boolean get() = !isCompleted && !isCancelled
    fun cancelling(): Boolean {
        if (isCancelled) return true
        for (s in scopes) if (s.failure != null) return true
        return false
    }
    fun step() {
        if (isCompleted || running) return
        if (handle == 0L) { started = true; handle = kxCoStart(body); KxSched.live.add(this) }
        val prev = KxSched.current
        KxSched.current = this
        running = true
        val done = kxCoResume(handle)
        running = false
        KxSched.current = prev
        if (done) { kxCoDestroy(handle); handle = 0L; finish() }
    }
    fun finish() {
        isCompleted = true
        body = {}
        scope = null
        while (scopes.size > 0) scopes.removeAt(scopes.size - 1)
        KxSched.dropLive(this)
        KxSched.wakeAll(waiters)
    }
    fun cancel() {
        if (isCompleted || isCancelled) return
        isCancelled = true
        val sc = scope
        if (sc != null) for (c in sc.children) c.cancel()
        if (!started) { finish(); return }
        KxSched.poke(this)
    }
    suspend fun join() {
        while (!isCompleted) {
            waiters.add(KxSched.me())
            kxSuspend()
        }
    }
    // Waits without observing cancellation (a cancelled parent still waits for its children to unwind).
    suspend fun joinQuiet() {
        while (!isCompleted) {
            waiters.add(KxSched.me())
            kxCoSuspend()
        }
    }
}
class Deferred<T>(val compute: suspend () -> T) {
    var result: T? = null
    var hasResult = false
    var failure: Throwable? = null
    val job: Job = Job { try { result = compute(); hasResult = true } catch (e: CancellationException) { } catch (e: Throwable) { failure = e } }
    val isCompleted: Boolean get() = job.isCompleted
    suspend fun await(): T {
        job.join()
        val f = failure
        if (f != null) throw f
        if (!hasResult) throw CancellationException("Deferred was cancelled")
        return result!!
    }
    fun cancel() { job.cancel() }
}
class CoroutineScope {
    val children = ArrayList<Job>()
    var failure: Throwable? = null
    var owner: Job? = null
    // supervisorScope: a failed child does not fail the scope; its exception goes to the handler instead.
    var supervisor = false
    var handler: CoroutineExceptionHandler? = null
    suspend fun yield() { kxYield() }
    val isActive: Boolean get() { val c = KxSched.current; return c == null || !c.cancelling() }
    fun ensureActive() { val c = KxSched.current; if (c != null && c.cancelling()) throw CancellationException("Job was cancelled") }
    // A child failed (or the body threw): the first failure wins; siblings and the scope's body are cancelled.
    fun fail(e: Throwable) {
        if (failure == null) failure = e
        for (c in children) c.cancel()
        val o = owner
        if (o != null) KxSched.poke(o)
    }
    fun launch(context: CoroutineContext = EmptyCoroutineContext, block: suspend CoroutineScope.() -> Unit): Job {
        val child = CoroutineScope()
        val parent = this
        val h = kxHandlerOf(context)
        child.handler = if (h != null) h else handler
        // launch(SupervisorJob()): not a child for failure purposes, its exception goes to the handler.
        val detached = kxIsSupervisor(context)
        val j = Job {
            kxRunScoped(child) { block(child) }
            val f = child.failure
            if (f != null) { if (detached) kxHandle(child.handler, f) else parent.childFailed(f, child.handler) }
        }
        j.scope = child; child.owner = j; j.scopes.add(child)
        // A detached job is not a child: the scope neither waits for it nor cancels it.
        if (!detached) children.add(j)
        KxSched.wake(j)
        return j
    }
    // A launched child failed: a supervisor hands the exception to the handler, a regular scope fails.
    fun childFailed(e: Throwable, h: CoroutineExceptionHandler?) {
        if (!supervisor) { fail(e); return }
        kxHandle(h, e)
    }
    fun <T> async(block: suspend CoroutineScope.() -> T): Deferred<T> {
        val child = CoroutineScope()
        child.handler = handler
        val parent = this
        val d = Deferred<T> {
            try { val r = block(child); child.joinChildren(); r }
            catch (e: CancellationException) {
                child.joinChildrenQuiet()
                val f = child.failure
                if (f != null) { if (!parent.supervisor) parent.fail(f); throw f }
                throw e
            }
            catch (e: Throwable) { child.fail(e); child.joinChildrenQuiet(); if (!parent.supervisor) parent.fail(e); throw e }
        }
        d.job.scope = child; child.owner = d.job; d.job.scopes.add(child)
        children.add(d.job); KxSched.wake(d.job)
        return d
    }
    suspend fun joinChildren() {
        var i = 0
        while (i < children.size) { children[i].join(); i++ }
        while (children.size > 0) children.removeAt(children.size - 1)
    }
    suspend fun joinChildrenQuiet() {
        var i = 0
        while (i < children.size) { children[i].joinQuiet(); i++ }
        while (children.size > 0) children.removeAt(children.size - 1)
    }
}
// Runs a scope's body and waits for its children; a failure is recorded in the scope (not rethrown here).
suspend fun kxRunScoped(s: CoroutineScope, body: suspend () -> Unit) {
    try { body(); s.joinChildren() }
    catch (e: CancellationException) { }
    catch (e: Throwable) { s.fail(e) }
    s.joinChildrenQuiet()
}
fun runBlocking(block: suspend CoroutineScope.() -> Unit) {
    val s = CoroutineScope()
    val root = Job { kxRunScoped(s) { block(s) } }
    s.owner = root; root.scope = s; root.scopes.add(s)
    KxSched.wake(root)
    KxSched.runUntil(root)
    if (KxSched.current == null) KxSched.drainDetached()
    val f = s.failure
    if (f != null) throw f
}
suspend fun coroutineScope(block: suspend CoroutineScope.() -> Unit) {
    val j = KxSched.me()
    val s = CoroutineScope()
    s.owner = j
    j.scopes.add(s)
    var cancelled = false
    try { block(s); s.joinChildren() }
    catch (e: CancellationException) { cancelled = true }
    catch (e: Throwable) { s.fail(e) }
    s.joinChildrenQuiet()
    j.scopes.removeAt(j.scopes.size - 1)
    val f = s.failure
    if (f != null) throw f
    if (cancelled) throw CancellationException("Job was cancelled")
}
suspend fun supervisorScope(block: suspend CoroutineScope.() -> Unit) {
    val j = KxSched.me()
    val s = CoroutineScope()
    s.owner = j
    s.supervisor = true
    if (j.scopes.size > 0) s.handler = j.scopes[j.scopes.size - 1].handler
    j.scopes.add(s)
    var cancelled = false
    try { block(s); s.joinChildren() }
    catch (e: CancellationException) { cancelled = true }
    catch (e: Throwable) { s.fail(e) }
    s.joinChildrenQuiet()
    j.scopes.removeAt(j.scopes.size - 1)
    val f = s.failure
    if (f != null) throw f
    if (cancelled) throw CancellationException("Job was cancelled")
}
class CoroutineExceptionHandler(val handler: (CoroutineContext, Throwable) -> Unit) : CoroutineContext
class KxSupervisorJob : CoroutineContext
fun SupervisorJob(): CoroutineContext = KxSupervisorJob()
class KxCtx(val a: CoroutineContext, val b: CoroutineContext) : CoroutineContext
operator fun CoroutineContext.plus(other: CoroutineContext): CoroutineContext = KxCtx(this, other)
fun kxHandlerOf(c: CoroutineContext): CoroutineExceptionHandler? {
    if (c is CoroutineExceptionHandler) return c
    if (c is KxCtx) { val r = kxHandlerOf(c.b); if (r != null) return r; return kxHandlerOf(c.a) }
    return null
}
fun kxHandle(h: CoroutineExceptionHandler?, e: Throwable) { if (h != null) h.handler(EmptyCoroutineContext, e) }
fun kxIsSupervisor(c: CoroutineContext): Boolean {
    if (c is KxSupervisorJob) return true
    if (c is KxCtx) return kxIsSupervisor(c.a) || kxIsSupervisor(c.b)
    return false
}
suspend fun kxYield() { KxSched.wake(KxSched.me()); kxSuspend() }
suspend fun yield() { kxYield() }
suspend fun delay(ms: Long) {
    val j = KxSched.me()
    j.wakeAt = KxSched.now + ms
    KxSched.addTimer(j)
    kxSuspend()
}
fun currentTimeVirtual(): Long = KxSched.now
suspend fun joinAll(jobs: List<Job>) { for (j in jobs) j.join() }
suspend fun <T> awaitAll(ds: List<Deferred<T>>): List<T> { val r = ArrayList<T>(); for (d in ds) r.add(d.await()); return r }
class Channel<T>(val capacity: Int) {
    val buf = ArrayList<T>()
    val receivers = ArrayList<Job>()
    val senders = ArrayList<Job>()
    var sent = 0L
    var received = 0L
    var isClosedForSend = false
    val isEmpty: Boolean get() = buf.size == 0
    val isClosedForReceive: Boolean get() = isClosedForSend && buf.size == 0
    suspend fun send(x: T) {
        if (isClosedForSend) throw IllegalStateException("Channel was closed")
        while (capacity > 0 && buf.size >= capacity) { senders.add(KxSched.me()); kxSuspend() }
        buf.add(x)
        val ticket = sent
        sent++
        KxSched.wakeAll(receivers)
        // Rendezvous (capacity 0): the sender resumes once a receiver took its element.
        while (capacity == 0 && received <= ticket) { senders.add(KxSched.me()); kxSuspend() }
    }
    fun trySend(x: T): Boolean {
        if (isClosedForSend || (capacity >= 0 && buf.size >= capacity)) return false
        buf.add(x); sent++; KxSched.wakeAll(receivers); return true
    }
    suspend fun receive(): T {
        while (buf.size == 0) {
            if (isClosedForSend) throw ClosedReceiveChannelException("Channel was closed")
            receivers.add(KxSched.me()); kxSuspend()
        }
        received++
        KxSched.wakeAll(senders)
        return buf.removeAt(0)
    }
    fun close() { isClosedForSend = true; KxSched.wakeAll(receivers) }
    suspend fun consumeEach(f: suspend (T) -> Unit) {
        while (true) {
            while (buf.size == 0 && !isClosedForSend) { receivers.add(KxSched.me()); kxSuspend() }
            if (buf.size == 0) return
            received++
            KxSched.wakeAll(senders)
            f(buf.removeAt(0))
        }
    }
}
class FlowCollector<T>(val sink: suspend (T) -> Unit) { suspend fun emit(x: T) { sink(x) } }
class Flow<T>(val run: suspend (FlowCollector<T>) -> Unit) {
    suspend fun collect(f: suspend (T) -> Unit) { run(FlowCollector<T>(f)) }
    suspend fun toList(): List<T> { val r = ArrayList<T>(); collect { r.add(it) }; return r }
}
fun <T> flow(block: suspend FlowCollector<T>.() -> Unit): Flow<T> = Flow<T> { c -> block(c) }
fun <T> flowOf(xs: List<T>): Flow<T> = Flow<T> { c -> for (x in xs) c.emit(x) }
fun <T, R> Flow<T>.map(f: (T) -> R): Flow<R> { val src = this; return Flow<R> { out -> src.collect { out.emit(f(it)) } } }
fun <T> Flow<T>.filter(p: (T) -> Boolean): Flow<T> { val src = this; return Flow<T> { out -> src.collect { if (p(it)) out.emit(it) } } }
// ---- kotlin.coroutines (low level): Continuation, startCoroutine, createCoroutine, suspendCoroutine ----
interface CoroutineContext
object EmptyCoroutineContext : CoroutineContext
interface Continuation<T> {
    val context: CoroutineContext
    fun resumeWith(result: Result<T>)
}
class KcLambdaContinuation<T>(override val context: CoroutineContext, val f: (Result<T>) -> Unit) : Continuation<T> {
    override fun resumeWith(result: Result<T>) { f(result) }
}
fun <T> Continuation(context: CoroutineContext, resumeWith: (Result<T>) -> Unit): Continuation<T> = KcLambdaContinuation<T>(context, resumeWith)
fun <T> Continuation<T>.resume(value: T) { resumeWith(Result.success(value)) }
fun <T> Continuation<T>.resumeWithException(exception: Throwable) { resumeWith(Result.failure<T>(exception)) }
// Drives one started coroutine (the root of a chain of awaiting LLVM coroutines); resuming any continuation of the
// chain resumes the root, whose awaits resume down to the suspended leaf.
class KcDriver(val body: suspend () -> Unit) {
    var handle = 0L
    var running = false
    var again = false
    var done = false
    fun pump() {
        if (done) return
        if (running) { again = true; return }
        if (handle == 0L) handle = kxCoStart(body)
        val prev = KcCur.driver
        KcCur.driver = this
        running = true
        var fin = kxCoResume(handle)
        while (!fin && again) { again = false; fin = kxCoResume(handle) }
        again = false
        running = false
        KcCur.driver = prev
        if (fin) { kxCoDestroy(handle); handle = 0L; done = true; body = {} }
    }
}
object KcCur { var driver: KcDriver? = null }
class KcSafeContinuation<T>(override val context: CoroutineContext, val driver: KcDriver) : Continuation<T> {
    var result: Result<T>? = null
    override fun resumeWith(result: Result<T>) {
        if (this.result != null) throw IllegalStateException("Already resumed")
        this.result = result
        driver.pump()
    }
}
suspend fun <T> suspendCoroutine(block: (Continuation<T>) -> Unit): T {
    val d = KcCur.driver
    if (d == null) throw IllegalStateException("suspendCoroutine outside startCoroutine")
    val c = KcSafeContinuation<T>(EmptyCoroutineContext, d)
    block(c)
    while (c.result == null) kxCoSuspend()
    return c.result!!.getOrThrow()
}
class KcStarter<T>(val f: suspend () -> T, val completion: Continuation<T>) : Continuation<Unit> {
    override val context: CoroutineContext get() = completion.context
    var started = false
    val driver: KcDriver = KcDriver {
        var res: Result<T>? = null
        try { res = Result.success(f()) } catch (e: Throwable) { res = Result.failure<T>(e) }
        completion.resumeWith(res!!)
    }
    override fun resumeWith(result: Result<Unit>) {
        if (started) throw IllegalStateException("Already resumed")
        started = true
        driver.pump()
    }
}
fun <T> (suspend () -> T).createCoroutine(completion: Continuation<T>): Continuation<Unit> = KcStarter<T>(this, completion)
fun <T> (suspend () -> T).startCoroutine(completion: Continuation<T>) { createCoroutine(completion).resume(Unit) }
fun <R, T> (suspend R.() -> T).startCoroutine(receiver: R, completion: Continuation<T>) {
    val f = this
    val g: suspend () -> T = { f(receiver) }
    g.startCoroutine(completion)
}
fun <R, T> (suspend (R) -> T).startCoroutine(receiver: R, completion: Continuation<T>) {
    val f = this
    val g: suspend () -> T = { f(receiver) }
    g.startCoroutine(completion)
}
fun <R, T> (suspend R.() -> T).createCoroutine(receiver: R, completion: Continuation<T>): Continuation<Unit> {
    val f = this
    val g: suspend () -> T = { f(receiver) }
    return g.createCoroutine(completion)
}
"""
