package newkotlin

/**
 * Mutable frontend/codegen data owned by one compilation, not by the process. The small compatibility objects
 * (LamIds, RefLambdas, etc.) are views of this context so parser helpers and backend restarts share it, but a later
 * compileToIr starts with empty caches/counters and cannot retain ASTs or names from an earlier compilation.
 * The compiler pipeline is single-threaded; concurrent callers must serialize compileToIr calls.
 */
class CompilationContext {
    val constFields = HashSet<String>()
    val refTypeArgs = HashMap<NameE, List<TypeRef>>()
    val externs = HashSet<String>()
    val ifaceSuperArgs = HashMap<String, Map<String, List<TypeRef>>>()
    val overloads = HashMap<String, List<FunD>>()
    val refLambdas = HashMap<UnE, LambdaE>()
    val refSetters = HashMap<UnE, LambdaE>()
    val recvCaps = HashMap<Block, Block>()
    val thisCaps = HashMap<Block, Block>()
    val suspConvLambdas = HashMap<String, LambdaE>()
    val recvLambdas = HashMap<Block, Block>()
    val typeParamBounds = HashMap<String, TypeRef>()
    val unboundedTypeParams = HashSet<String>()
    val recvLocalClasses = HashMap<String, String>()
    val implicitLocalClasses = HashSet<String>()
    val recvCapTypes = HashMap<String, TypeRef>()
    val liftedLocals = HashSet<String>()
    val nestedClasses = HashMap<String, String>()
    val ctxRecvFnTypes = ArrayList<Pair<List<TypeRef>, Int>>()
    val recvCallNames = HashMap<CallE, NameE>()
    var nextLambda = 0
    var nextArg = 0
    var usesRef = false
}

internal var compilationContext = CompilationContext()
