package newkotlin

/*
 * kotlinx libraries written in Kotlin and merged into a program that uses them (like Prelude.kt):
 *
 * - kotlinx.coroutines subset: `runBlocking`, `coroutineScope`, `launch`, `async`/`await`, `Job` (join, cancel,
 *   isActive/isCompleted), `joinAll`, `awaitAll`, `yield`, `delay`, `Channel` (send/receive/trySend/close/consumeEach)
 *   and cold `Flow` (`flow { emit(x) }`, `flowOf`, `map`, `filter`, `collect`, `toList`). `suspend` is accepted on
 *   functions and function types. Coroutines are stackful (runtime.c `rt_co_*`, one mmap'ed stack per coroutine)
 *   on a single-threaded scheduler: `join`, `await`, `receive`, `send` (full or rendezvous channel), `yield` and
 *   `delay` really suspend the calling coroutine. `delay` uses virtual time (timers ordered by wake-up time).
 *   `runBlocking`/`coroutineScope` return Unit (generic `T = Unit` parameters are not supported yet).
 * - kotlinx.serialization subset: `@Serializable` classes get a generated `KSerializer` (`X.serializer()`), `Json`
 *   with `encodeToString`/`decodeFromString(serializer, ...)`, `parseToJsonElement`, and the `JsonElement` tree
 *   (`JsonObject`, `JsonArray`, `JsonPrimitive`, `JsonNull`). Supported property types: Int, Long, Double, Boolean,
 *   String, other @Serializable classes, List<T> of those, and nullable variants.
 */

val KOTLINX_COROUTINES = """
external fun rt_co_new(unused: Long): Long
external fun rt_co_resume(h: Long): Int
external fun rt_co_suspend(): Unit
class CancellationException(message: String) : IllegalStateException(message)
class ClosedReceiveChannelException(message: String) : IllegalStateException(message)
object KxSched {
    val ready = ArrayList<Job>()
    val timers = ArrayList<Job>()
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
    fun runUntil(root: Job) {
        while (!root.isCompleted) {
            if (ready.size > 0) { val j = ready.removeAt(0); j.step() }
            else if (timers.size > 0) { val j = timers.removeAt(0); if (j.wakeAt > now) now = j.wakeAt; j.step() }
            else throw IllegalStateException("runBlocking: deadlock, no coroutine can make progress")
        }
    }
    fun me(): Job {
        val c = current
        if (c == null) throw IllegalStateException("suspend function called outside a coroutine")
        return c
    }
}
fun kxCoEntry(id: Long) {
    val j = KxSched.me()
    j.body()
}
open class Job(body0: () -> Unit) {
    var body: () -> Unit = body0
    var handle = 0L
    var started = false
    var isCompleted = false
    var isCancelled = false
    var wakeAt = 0L
    val waiters = ArrayList<Job>()
    val isActive: Boolean get() = !isCompleted && !isCancelled
    fun step() {
        if (isCompleted) return
        if (handle == 0L) { started = true; handle = rt_co_new(0L) }
        val prev = KxSched.current
        KxSched.current = this
        val done = rt_co_resume(handle)
        KxSched.current = prev
        if (done != 0) { handle = 0L; finish() }
    }
    fun finish() {
        isCompleted = true
        body = {}
        KxSched.wakeAll(waiters)
    }
    fun cancel() { if (!started && !isCompleted) { isCancelled = true; finish() } }
    fun join() {
        while (!isCompleted) {
            waiters.add(KxSched.me())
            rt_co_suspend()
        }
    }
}
class Deferred<T>(val compute: () -> T) {
    var result: T? = null
    val job: Job = Job { result = compute() }
    val isCompleted: Boolean get() = job.isCompleted
    fun await(): T { job.join(); return result!! }
    fun cancel() { job.cancel() }
}
class CoroutineScope {
    val children = ArrayList<Job>()
    // Members too, so calls inside nested lambdas of a scope builder resolve.
    fun yield() { kxYield() }
    fun launch(block: CoroutineScope.() -> Unit): Job {
        val child = CoroutineScope()
        val j = Job { with(child, block); child.joinChildren() }
        children.add(j); KxSched.wake(j)
        return j
    }
    fun <T> async(block: CoroutineScope.() -> T): Deferred<T> {
        val child = CoroutineScope()
        val d = Deferred<T> { val r = with(child, block); child.joinChildren(); r }
        children.add(d.job); KxSched.wake(d.job)
        return d
    }
    fun joinChildren() {
        var i = 0
        while (i < children.size) { children[i].join(); i++ }
        while (children.size > 0) children.removeAt(children.size - 1)
    }
}
fun runBlocking(block: CoroutineScope.() -> Unit) {
    if (KxSched.current != null) throw IllegalStateException("nested runBlocking is not supported")
    val s = CoroutineScope()
    val root = Job { with(s, block); s.joinChildren() }
    KxSched.wake(root)
    KxSched.runUntil(root)
}
fun coroutineScope(block: CoroutineScope.() -> Unit) {
    val s = CoroutineScope()
    with(s, block)
    s.joinChildren()
}
fun kxYield() { KxSched.wake(KxSched.me()); rt_co_suspend() }
fun yield() { kxYield() }
fun delay(ms: Long) {
    val j = KxSched.me()
    j.wakeAt = KxSched.now + ms
    KxSched.addTimer(j)
    rt_co_suspend()
}
fun currentTimeVirtual(): Long = KxSched.now
fun joinAll(jobs: List<Job>) { for (j in jobs) j.join() }
fun <T> awaitAll(ds: List<Deferred<T>>): List<T> { val r = ArrayList<T>(); for (d in ds) r.add(d.await()); return r }
class Channel<T>(val capacity: Int) {
    val buf = ArrayList<T>()
    val receivers = ArrayList<Job>()
    val senders = ArrayList<Job>()
    var sent = 0L
    var received = 0L
    var isClosedForSend = false
    val isEmpty: Boolean get() = buf.size == 0
    val isClosedForReceive: Boolean get() = isClosedForSend && buf.size == 0
    fun send(x: T) {
        if (isClosedForSend) throw IllegalStateException("Channel was closed")
        while (capacity > 0 && buf.size >= capacity) { senders.add(KxSched.me()); rt_co_suspend() }
        buf.add(x)
        val ticket = sent
        sent++
        KxSched.wakeAll(receivers)
        // Rendezvous (capacity 0): the sender resumes once a receiver took its element.
        while (capacity == 0 && received <= ticket) { senders.add(KxSched.me()); rt_co_suspend() }
    }
    fun trySend(x: T): Boolean {
        if (isClosedForSend || (capacity >= 0 && buf.size >= capacity)) return false
        buf.add(x); sent++; KxSched.wakeAll(receivers); return true
    }
    fun receive(): T {
        while (buf.size == 0) {
            if (isClosedForSend) throw ClosedReceiveChannelException("Channel was closed")
            receivers.add(KxSched.me()); rt_co_suspend()
        }
        received++
        KxSched.wakeAll(senders)
        return buf.removeAt(0)
    }
    fun close() { isClosedForSend = true; KxSched.wakeAll(receivers) }
    fun consumeEach(f: (T) -> Unit) {
        while (true) {
            while (buf.size == 0 && !isClosedForSend) { receivers.add(KxSched.me()); rt_co_suspend() }
            if (buf.size == 0) return
            received++
            KxSched.wakeAll(senders)
            f(buf.removeAt(0))
        }
    }
}
class FlowCollector<T>(val sink: (T) -> Unit) { fun emit(x: T) { sink(x) } }
class Flow<T>(val run: (FlowCollector<T>) -> Unit) {
    fun collect(f: (T) -> Unit) { run(FlowCollector<T>(f)) }
    fun toList(): List<T> { val r = ArrayList<T>(); collect { r.add(it) }; return r }
}
fun <T> flow(block: FlowCollector<T>.() -> Unit): Flow<T> = Flow<T> { c -> with(c, block) }
fun <T> flowOf(xs: List<T>): Flow<T> = Flow<T> { c -> for (x in xs) c.emit(x) }
fun <T, R> Flow<T>.map(f: (T) -> R): Flow<R> { val src = this; return Flow<R> { out -> src.collect { out.emit(f(it)) } } }
fun <T> Flow<T>.filter(p: (T) -> Boolean): Flow<T> { val src = this; return Flow<T> { out -> src.collect { if (p(it)) out.emit(it) } } }
"""

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

val COROUTINES_USE = Regex("""\brunBlocking\b|\bcoroutineScope\b|\bChannel<|\bflow(Of)?\s*[<{(]|\bsuspend\b""")
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
