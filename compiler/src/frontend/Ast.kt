package newkotlin

class TypeRef(val name: String, val args: List<TypeRef>, val nullable: Boolean) {
    override fun toString() = name + (if (args.isEmpty()) "" else args.joinToString(",", "<", ">")) + (if (nullable) "?" else "")
}

sealed class E { var line = 0 }
class IntL(val v: Long, val long: Boolean, val unsigned: Boolean = false) : E()
/** `x++` / `x--` used as an expression: yields the old value. */
class PostIncE(val target: E, val op: String) : E()
class CharL(val v: Long) : E()
class DblL(val v: Double) : E()
class BoolL(val v: Boolean) : E()
class NullL : E()
class StrL(val parts: List<Any>) : E() // String or E
class NameE(val n: String) : E()
class CallE(val callee: E, val typeArgs: List<TypeRef>, val args: List<E>, val lambda: Block?) : E()
/** Named call argument `name = e` (only `copy` of data classes accepts these). */
class NamedE(val name: String, val e: E) : E()
/** `e is T` / `e !is T`. */
class IsE(val e: E, val type: TypeRef, val neg: Boolean) : E()
/** `e as T` / `e as? T`. */
class AsE(val e: E, val type: TypeRef, val safe: Boolean) : E()
class MemberE(val recv: E, val n: String, val safe: Boolean) : E()
class IndexE(val recv: E, val idx: E) : E()
class BinE(val op: String, val l: E, val r: E) : E()
class UnE(val op: String, val e: E) : E()
class IfE(val cond: E, val th: Block, val el: Block?) : E()
/** Lambda literal used as a value (function-typed); compiled to a closure object implementing a `$Fn...` interface. */
class LambdaE(val body: Block) : E()
/** `catch (name: type) { body }` clause of a [TryE]. */
class Catch(val name: String, val type: TypeRef, val body: Block)
/** `try { body } catch (...) { ... } finally { ... }` (an expression). */
class TryE(val body: Block, val catches: List<Catch>, val fin: Block?) : E()
/** `conds == null` is the else branch. */
class WhenE(val subj: E?, val branches: List<Pair<List<E>?, Block>>) : E()

sealed class S { var line = 0 }
class VarS(val name: String, val isVar: Boolean, val type: TypeRef?, val init: E) : S()
class AssignS(val target: E, val op: String, val value: E) : S()
class IncS(val target: E, val op: String) : S()
/** `isDo`: `do { body } while (cond)` (the body runs before the first test). */
class WhileS(val cond: E, val body: Block, val isDo: Boolean = false) : S()
class ForS(val name: String, val iter: E, val body: Block) : S()
class RetS(val e: E?) : S()
class ExprS(val e: E) : S()
/** `break` / `continue` (unlabeled: innermost loop). */
class JumpS(val isBreak: Boolean) : S()

/** `params` are lambda parameter names (empty = implicit `it`). */
class Block(val stmts: List<S>, val params: List<String> = emptyList(), val paramTypes: List<TypeRef?> = emptyList()) {
    /** Lambda identity, stable across code-generation restarts (assigned on first use). */
    var lamId = -1
    /** Memoized rewrite of this lambda body (outer `this` capture), so the rewritten body keeps stable identities. */
    var rewritten: Block? = null
}

object LamIds {
    private var next = 0
    fun id(b: Block): Int { if (b.lamId < 0) b.lamId = next++; return b.lamId }
}
/** `default`: default argument, evaluated at the call site (may refer to earlier parameters). */
class Param(val name: String, val type: TypeRef, val default: E? = null, /** `vararg`: [type] is the list type. */ val vararg: Boolean = false)
/** `typeParams`: generic type parameters (`fun <T> f(...)`); generic functions are monomorphized per type-argument list. */
class FunD(val name: String, val params: List<Param>, val ret: TypeRef?, val body: Block?, val exprBody: E?, val line: Int,
           val typeParams: List<String> = emptyList(),
           /** Extension receiver type (`fun Recv.name(...)`); resolved away by [Parser.program]. */
           val recv: TypeRef? = null)
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
/** `globals`: top-level properties, initialized in declaration order before `main`. */
class Program(val funs: List<FunD>, val classes: List<ClassD>, val ifaces: List<InterfaceD>, val globals: List<VarS> = emptyList(),
              /** Class name -> (companion object name, names the companion object declares, incl. its own name). */
              val companions: Map<String, Pair<String, Set<String>>> = emptyMap())
