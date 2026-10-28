package newkotlin

class TypeRef(val name: String, val args: List<TypeRef>, val nullable: Boolean) {
    override fun toString() = name + (if (args.isEmpty()) "" else args.joinToString(",", "<", ">")) + (if (nullable) "?" else "")
}

sealed class E { var line = 0 }
class IntL(val v: Long, val long: Boolean) : E()
class DblL(val v: Double) : E()
class BoolL(val v: Boolean) : E()
class NullL : E()
class StrL(val parts: List<Any>) : E() // String or E
class NameE(val n: String) : E()
class CallE(val callee: E, val typeArgs: List<TypeRef>, val args: List<E>, val lambda: Block?) : E()
class MemberE(val recv: E, val n: String, val safe: Boolean) : E()
class IndexE(val recv: E, val idx: E) : E()
class BinE(val op: String, val l: E, val r: E) : E()
class UnE(val op: String, val e: E) : E()
class IfE(val cond: E, val th: Block, val el: Block?) : E()
/** `conds == null` is the else branch. */
class WhenE(val subj: E?, val branches: List<Pair<List<E>?, Block>>) : E()

sealed class S { var line = 0 }
class VarS(val name: String, val isVar: Boolean, val type: TypeRef?, val init: E) : S()
class AssignS(val target: E, val op: String, val value: E) : S()
class IncS(val target: E, val op: String) : S()
class WhileS(val cond: E, val body: Block) : S()
class ForS(val name: String, val iter: E, val body: Block) : S()
class RetS(val e: E?) : S()
class ExprS(val e: E) : S()

class Block(val stmts: List<S>)
class Param(val name: String, val type: TypeRef)
class FunD(val name: String, val params: List<Param>, val ret: TypeRef?, val body: Block?, val exprBody: E?, val line: Int)
class FieldD(val name: String, val type: TypeRef, val isVar: Boolean)
class ClassD(val name: String, val fields: List<FieldD>, val line: Int)
class Program(val funs: List<FunD>, val classes: List<ClassD>)
