package newkotlin

// Parser-wide views of the CompilationContext (split out of Parser.kt).

/**
 * Upper bounds of type parameters, by "<declaration line>:<name>" (declarations are copied and renamed by later
 * passes but keep their line). Only consulted for semantics that depend on the bound (unchecked casts).
 */
object TypeParamBounds {
    val bounds get() = compilationContext.typeParamBounds
    val unbounded get() = compilationContext.unboundedTypeParams
}

/** Names of lifted local declarations (a local function wins over a member of an implicit receiver). */
/** Local classes inside lambdas ("name@line") -> the `$othisN` value holding the lambda's `this`. */
object RecvLocalClasses {
    val map get() = compilationContext.recvLocalClasses; val implicitOnly get() = compilationContext.implicitLocalClasses
    /** `$othisN` -> its concrete type, for a class that is itself the builder's type argument (see recvClasses). */
    val capType get() = compilationContext.recvCapTypes
}

object LiftedLocals { val names get() = compilationContext.liftedLocals; var usesRef: Boolean
    get() = compilationContext.usesRef
    set(value) { compilationContext.usesRef = value } }

/** Nested class -> the class it is declared in (both hoisted to top level): companions of outer classes are implicit receivers. */
object NestedClasses { val outer get() = compilationContext.nestedClasses }

internal var argTmp: Int
    get() = compilationContext.nextArg
    set(value) { compilationContext.nextArg = value }

/**
 * `context(C) R.(P) -> T` is the plain function type `(C, R, P) -> T`; its lambdas take `R` as `this`. The function
 * types written that way (argument lists, with the receiver's index), matched by NativeBackend.genLambda.
 */
object CtxRecvFnTypes {
    val all get() = compilationContext.ctxRecvFnTypes
    fun add(t: TypeRef, recvAt: Int) { if (all.none { it.second == recvAt && it.first.toString() == t.args.toString() }) all.add(t.args to recvAt) }
}
