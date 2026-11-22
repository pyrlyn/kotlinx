package newkotlin

// Value/type model, lookup tables and restart signals shared by NativeBackend (split out of NativeBackend.kt).
/** An SSA value (or constant) with its type. `owned` = caller holds a +1 reference (ref types only). */
class Val(val v: String, val t: Ty, val owned: Boolean = false)

internal class Local(val slot: String, val t: Ty, val isVar: Boolean)
internal class Scope { val vars = HashMap<String, Local>(); val refs = ArrayList<Local>() }
/** Lambdas synthesized for callable references, per reference node; survives code-generation restarts. */
internal object RefLambdas { val map get() = compilationContext.refLambdas; val setters get() = compilationContext.refSetters }
/** [suspend]: compiled as an LLVM coroutine (see genCoroutine); calls from coroutines await it. */
internal class FunSig(val name: String, val params: List<Ty>, val ret: Ty, val suspend: Boolean = false)
internal fun isSuspFnName(n: String) = n.startsWith("\$FnS$") || n.startsWith("\$FnRS$")
/** `$FnS` / `$FnRS` (suspend function types) act like `$Fn` / `$FnR` for inference. */
internal fun baseFn(n: String) = when (n) { "\$FnS" -> "\$Fn"; "\$FnRS" -> "\$FnR"; else -> n }
/** Object layout: { i64 refcount, ptr vtable, fields... }; vtable[0] = release function. */
/**
 * `fields`: inherited fields first (prefix layout), then own. `supers`: all superclasses and interfaces (transitive).
 * `ancestors`: superclass chain. `impl`: method name -> class whose body implements it (absent = abstract).
 */
internal class ClassInfo(val name: String, val fields: List<Pair<String, Ty>>, val supers: List<String> = emptyList(), val methods: Map<String, FunD> = emptyMap(),
                        val ancestors: List<String> = emptyList(), val impl: Map<String, String> = methods.keys.associateWith { name }) {
    val llType = "%C.$name"
    fun index(f: String) = fields.indexOfFirst { it.first == f }
    /** Whether an unqualified [n] names a field, method or property of this class. */
    fun has(n: String) = index(n) >= 0 || n in methods || "\$prop\$$n" in methods
}
internal class IfaceInfo(val name: String, val methods: Map<String, FunSig>)

/** libc functions callable by name from Kotlin/Native-style `platform.posix` code. */
internal val POSIX = mapOf(
    "getenv" to (listOf(Ty.Str) to Ty.RawPtr),
    "fopen" to (listOf(Ty.Str, Ty.Str) to Ty.RawPtr),
    "fseek" to (listOf(Ty.RawPtr, Ty.I64, Ty.I32) to Ty.I32),
    "ftell" to (listOf(Ty.RawPtr) to Ty.I64),
    "fread" to (listOf(Ty.RawPtr, Ty.I64, Ty.I64, Ty.RawPtr) to Ty.I64),
    "fclose" to (listOf(Ty.RawPtr) to Ty.I32),
)
internal val CONSTS = mapOf("SEEK_SET" to 0L, "SEEK_CUR" to 1L, "SEEK_END" to 2L)
internal val NANOS = setOf("kotlin.system.getTimeNanos", "getTimeNanos", "System.nanoTime", "kotlin.system.System.nanoTime")

/** Suffix of the vtable slot holding a bridged override's own implementation (see computeBridges). */
internal const val NARROW = "#narrow"
/** vtable entries before the interface-method slots (see genClass). */
internal const val VT_HEADER = 5
/** Standard exception types `throw` accepts, with the qualified name printed for an uncaught exception. */
internal val EXCEPTIONS = listOf("AssertionError", "Error", "Exception", "RuntimeException", "IllegalStateException",
    "IllegalArgumentException", "UnsupportedOperationException", "ArithmeticException", "IndexOutOfBoundsException",
    "NullPointerException", "ClassCastException", "NumberFormatException", "ConcurrentModificationException",
    "UninitializedPropertyAccessException")
    .associateWith { "java.lang.$it" } + mapOf("NoSuchElementException" to "java.util.NoSuchElementException")
/** Binary operators that call an `operator fun` on class/interface operands. */
/** `intArrayOf` -> `IntArray`: the constructor of the array a primitive `...ArrayOf` builds. */
internal val ARRAY_OF_CTOR = listOf("Int", "Long", "Double", "Float", "Byte", "Boolean", "Char", "UInt", "ULong", "Short", "UByte", "UShort")
    .associate { it.replaceFirstChar(Char::lowercaseChar).let { l -> if (it.startsWith("U")) "u" + it.drop(1).lowercase() else l } + "ArrayOf" to it + "Array" }
internal val OP_FUNS = mapOf(BinOp.PLUS to "plus", BinOp.MINUS to "minus", BinOp.TIMES to "times", BinOp.DIV to "div", BinOp.REM to "rem")

/** Native target: typed LLVM IR generation (linked by clang with runtime/runtime.c). */
/** Thrown when code needs an instance of a generic class that is not in the program yet; [compileGenerics] adds it and retries. */
class NeedInstance(val name: String, val args: List<TypeRef>) : Exception()
/** Code needs a synthesized class (a lambda's closure class); [compileGenerics] adds it and retries. */
class NeedClass(val cd: ClassD) : Exception()
/** Code needs a function-type interface `$Fn<params..., ret>`; [compileGenerics] adds it and retries. */
class NeedIface(val d: InterfaceD) : Exception()
/**
 * Several of the above found in one generation pass: a lambda whose closure class is missing is recorded and replaced
 * by a placeholder value, so the pass goes on and collects the other missing classes instead of restarting per lambda.
 */
class NeedBatch(val needs: List<Exception>) : Exception()

/** LLVM instructions of the arithmetic operators, by operand kind (see NativeBackend.arith). */
internal val FP_OPS = mapOf(BinOp.PLUS to "fadd", BinOp.MINUS to "fsub", BinOp.TIMES to "fmul", BinOp.DIV to "fdiv", BinOp.REM to "frem")
internal val UNSIGNED_OPS = mapOf(BinOp.PLUS to "add", BinOp.MINUS to "sub", BinOp.TIMES to "mul", BinOp.DIV to "udiv", BinOp.REM to "urem")
internal val SIGNED_OPS = mapOf(BinOp.PLUS to "add", BinOp.MINUS to "sub", BinOp.TIMES to "mul", BinOp.DIV to "sdiv", BinOp.REM to "srem")

/**
 * Deferred reference counting for possibly cyclic objects (GC implementations whose `caps` contain `deferred`,
 * e.g. --gc=epoch): retain/release of traced objects are logged with rt_gc_log_inc / rt_gc_log_dec instead of
 * touching the header, stores of reference fields into traced objects are release stores followed by
 * rt_gc_write_barrier, collector traversal loads are acquire loads, and FBIP reuse is off for traced classes.
 * Acyclic objects keep inline RC. Set by the driver before code generation.
 */
var deferredRc = false

/** Borrowed field-read locals (see `borrowableFieldLocal`); NEWKOTLIN_NO_BORROW_FIELD_LOCALS=1 turns them off (A/B, tests). */
var borrowFieldLocals = true

/** Lambda-with-receiver bodies with `this` added as first parameter; kept across code-generation restarts (stable ids). */
internal object RecvCaps { val map get() = compilationContext.recvCaps }
internal object ThisCaps { val map get() = compilationContext.thisCaps }
internal object SuspConvLambdas { val map get() = compilationContext.suspConvLambdas }
object RecvLambdas { val map get() = compilationContext.recvLambdas }
