package newkotlin

/** A type as written in source: a name, type arguments and nullability. */
class TypeRef(val name: String, val args: List<TypeRef>, val nullable: Boolean) {
    override fun toString() = name + (if (args.isEmpty()) "" else args.joinToString(",", "<", ">")) + (if (nullable) "?" else "")
}

/** Pseudo type `$GetterType<g>`: the inferred return type of getter `g` (setters of untyped delegated `var`s). */
const val GETTER_TYPE = "\$GetterType"

/** An expression node; [line] is its source line, for errors. */
/** A piece of a string template: literal text ([StrText]) or an interpolated expression ([E]). */
sealed interface StrPart
class StrText(val s: String) : StrPart
sealed class E : StrPart { var line = 0 }
class IntL(val v: Long, val long: Boolean, val unsigned: Boolean = false) : E()
/** `x++` / `x--` used as an expression: yields the old value. */
class PostIncE(val target: E, val op: String) : E()
class CharL(val v: Long) : E()
class DblL(val v: Double, val f32: Boolean = false) : E()
class BoolL(val v: Boolean) : E()
class NullL : E()
class StrL(val parts: List<StrPart>) : E() {
    /** No interpolation: the literal is [text]. */
    val isConstant: Boolean get() = parts.all { it is StrText }
    /** The text of a constant template. */
    val text: String get() = parts.joinToString("") { (it as StrText).s }
}
/** A string template from literal text pieces and expressions (`strL("a=", e)`). */
fun strL(vararg ps: Any): StrL = StrL(ps.map { if (it is String) StrText(it) else it as E })
class NameE(val n: String) : E()
class CallE(val callee: E, val typeArgs: List<TypeRef>, val args: List<E>, val lambda: Block?) : E() {
    /** Written as `a name b`: builtin non-infix members (Int.rem) cannot be called this way, so extensions win. */
    var infix = false
}
/** Named call argument `name = e` (only `copy` of data classes accepts these). */
class NamedE(val name: String, val e: E) : E()
/** `e is T` / `e !is T`. */
class IsE(val e: E, val type: TypeRef, val neg: Boolean) : E()
/** `e as T` / `e as? T`. */
class AsE(val e: E, val type: TypeRef, val safe: Boolean) : E()
class MemberE(val recv: E, val n: String, val safe: Boolean) : E()
class IndexE(val recv: E, val idx: E) : E()
/** Binary operators, including the built-in infix functions the parser folds into [BinE]; [sym] is the source spelling. */
enum class BinOp(val sym: String) {
    OR("||"), AND("&&"),
    EQ("=="), NE("!="), IDENT("==="), NOT_IDENT("!=="),
    LT("<"), GT(">"), LE("<="), GE(">="),
    IN("in"), NOT_IN("!in"),
    ELVIS("?:"),
    BIT_AND("and"), BIT_OR("or"), XOR("xor"), SHL("shl"), SHR("shr"), USHR("ushr"), UNTIL("until"), DOWN_TO("downTo"), STEP("step"),
    RANGE(".."),
    PLUS("+"), MINUS("-"),
    TIMES("*"), DIV("/"), REM("%");

    override fun toString() = sym

    companion object {
        private val bySym = entries.associateBy { it.sym }
        /** The operator spelled [s]; an unknown spelling is an internal error, never a silent fallback. */
        fun of(s: String): BinOp = bySym[s] ?: throw IllegalArgumentException("unknown binary operator '$s'")
        fun ofOrNull(s: String): BinOp? = bySym[s]
    }
}

/** Unary operators; [REF] is a callable reference `recv::name` (the name is [UnE.ref]), [CLASS_LIT] is `e::class`. */
enum class UnOp(val sym: String) {
    NEG("-"), PLUS("+"), NOT("!"), NOT_NULL("!!"), SPREAD("*"), CLASS_LIT("#class"), REF("::");

    override fun toString() = sym
}

class BinE(val op: BinOp, val l: E, val r: E) : E()
/** [ref] is the referenced name exactly when [op] is [UnOp.REF]. */
class UnE(val op: UnOp, val e: E, val ref: String?) : E() {
    /** A non-reference unary operator. */
    constructor(op: UnOp, e: E) : this(op, e, null)
    init { require((op == UnOp.REF) == (ref != null)) { "UnE: callable reference name iff op is REF" } }
}
class IfE(val cond: E, val th: Block, val el: Block?) : E()
/** Lambda literal used as a value (function-typed); compiled to a closure object implementing a `$Fn...` interface. */
class LambdaE(val body: Block) : E()
/** `catch (name: type) { body }` clause of a [TryE]. */
class Catch(val name: String, val type: TypeRef, val body: Block)
/** `try { body } catch (...) { ... } finally { ... }` (an expression). */
class TryE(val body: Block, val catches: List<Catch>, val fin: Block?) : E()
/** `conds == null` is the else branch. */
/** One `when` branch: its conditions (null for `else`) and its body. */
data class WhenBranch(val conds: List<E>?, val body: Block)
class WhenE(val subj: E?, val branches: List<WhenBranch>) : E()

/** A statement node; [line] is its source line, for errors. */
sealed class S { var line = 0 }
class VarS(val name: String, val isVar: Boolean, val type: TypeRef?, val init: E) : S()
class AssignS(val target: E, val op: String, val value: E) : S()
class IncS(val target: E, val op: String) : S()
/** `isDo`: `do { body } while (cond)` (the body runs before the first test). */
class WhileS(val cond: E, val body: Block, val isDo: Boolean = false) : S() { var label: String? = null }
class ForS(val name: String, val iter: E, val body: Block) : S() { var label: String? = null }
class RetS(val e: E?) : S()
class ExprS(val e: E) : S()
/** `break` / `continue` (unlabeled: innermost loop). */
class JumpS(val isBreak: Boolean) : S() { /** `break@l` / `continue@l`. */ var label: String? = null }

/**
 * A function, class, object or interface declared inside a block (`d` is a [FunD], [ClassD] or [InterfaceD]).
 * Only the parser creates it; `Parser.liftLocals` replaces every one before later passes run.
 */
class LocalDeclS(val d: Any) : S()

/** `params` are lambda parameter names (empty = implicit `it`). */
class Block(val stmts: List<S>, val params: List<String> = emptyList(), val paramTypes: List<TypeRef?> = emptyList()) {
    /** Lambda identity, stable across code-generation restarts (assigned on first use). */
    var lamId = -1
    /** Memoized rewrite of this lambda body (outer `this` capture), so the rewritten body keeps stable identities. */
    var rewritten: Block? = null
}

/** `Owner.name` of every `const val` member (declared in a class or object body), for `isPermanent()`. */
object ConstFields { val names get() = compilationContext.constFields }

/**
 * Type arguments written on a type-name receiver of a callable reference (`Array<String>::firstElement`), by the
 * receiver's NameE (references resolve by the type name; the arguments pick the instance, e.g. `ArrayList<String>`).
 */
object RefTypeArgs { val map get() = compilationContext.refTypeArgs }

/** Names of top-level `external fun` declarations: implemented in C, linked via `--link`, called through the C ABI. */
object Externs { val names get() = compilationContext.externs }
/** Numbers lambda blocks once, so each gets a stable id. */
object LamIds {
    fun id(b: Block): Int { if (b.lamId < 0) b.lamId = compilationContext.nextLambda++; return b.lamId }
}
/** `default`: default argument, evaluated at the call site (may refer to earlier parameters). */
class Param(val name: String, val type: TypeRef, val default: E? = null, /** `vararg`: [type] is the list type. */ val vararg: Boolean = false,
            /** Context parameter (`context(c: T) fun f()`): filled from the implicit values in scope at the call. */ val ctx: Boolean = false)
/** `typeParams`: generic type parameters (`fun <T> f(...)`); generic functions are monomorphized per type-argument list. */
class FunD(val name: String, val params: List<Param>, val ret: TypeRef?, val body: Block?, val exprBody: E?, val line: Int,
           val typeParams: List<String> = emptyList(),
           /** Extension receiver type (`fun Recv.name(...)`); resolved away by [Parser.program]. */
           val recv: TypeRef? = null,
           /** `suspend fun`: compiled as an LLVM coroutine. Every rewrite that copies a FunD carries it over. */
           val isSuspend: Boolean = false) {
    /** Whether a call with [n] explicit arguments fits (context parameters are never passed explicitly). */
    fun takesArgs(n: Int) = params.filter { !it.ctx }.let { ps -> ps.any { it.vararg } || n in ps.count { it.default == null }..ps.size }
}
/** `type == null` (inferred) only for body properties, which have `init`. */
class FieldD(val name: String, val type: TypeRef?, val isVar: Boolean, val init: E? = null, val lateinit: Boolean = false)
/**
 * `fields`: constructor properties, then body properties. `ctorParams`: the primary constructor's parameters in order
 * (properties and plain parameters). `inits`: body properties (FieldD) and `init` blocks (Block) in declaration order.
 */
class ClassD(val name: String, val fields: List<FieldD>, val supers: List<String>, val methods: List<FunD>, val line: Int,
             val ctorParams: List<Param> = fields.map { Param(it.name, it.type!!) }, val inits: List<Any> = emptyList(),
             val superArgs: List<E>? = null, val typeParams: List<String> = emptyList(),
             /** Enum class: entries (name, constructor arguments); `name` and `ordinal` are the first two properties. */
             val enumEntries: List<Pair<String, List<E>>>? = null,
             /** `value class`: one property; equality and toString are structural on it. */
             val isValue: Boolean = false,
             /** Type arguments of generic supertypes by supertype name (`: Base<String>(), I<Int>`). */
             val superTypeArgs: Map<String, List<TypeRef>> = emptyMap()) {
    /** The superclass is the one supertype written with constructor arguments (`: Base(...)`). */
    val superClass: String? get() = if (superArgs != null) supers.first() else null
    /** Needs a constructor function (plain parameters, initializers or a superclass), not just field stores. */
    val hasCtorBody get() = inits.isNotEmpty() || ctorParams.size != fields.size || superArgs != null
}
class InterfaceD(val name: String, val methods: List<FunD>, val line: Int, val typeParams: List<String> = emptyList(),
                 /** All super-interfaces (transitively), filled by the parser. */ val supers: List<String> = emptyList())
/**
 * Type arguments of generic super-interfaces (direct and inherited) by interface name: `interface B : A<String>` ->
 * "B" -> {"A": [String]}. Generics instantiates `A$String` and points B's super at it.
 */
object IfaceSuperArgs { val map get() = compilationContext.ifaceSuperArgs }
/** `globals`: top-level properties, initialized in declaration order before `main`. */
class Program(val funs: List<FunD>, val classes: List<ClassD>, val ifaces: List<InterfaceD>, val globals: List<VarS> = emptyList(),
              /** Class name -> (companion object name, names the companion object declares, incl. its own name). */
              val companions: Map<String, Pair<String, Set<String>>> = emptyMap())
