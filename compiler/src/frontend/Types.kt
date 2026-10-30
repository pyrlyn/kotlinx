package newkotlin

sealed class Ty {
    object I8 : Ty() { override fun toString() = "Byte" }
    object I32 : Ty() { override fun toString() = "Int" }
    object I64 : Ty() { override fun toString() = "Long" }
    object U32 : Ty() { override fun toString() = "UInt" }
    object U64 : Ty() { override fun toString() = "ULong" }
    object I1 : Ty() { override fun toString() = "Boolean" }
    object F32 : Ty() { override fun toString() = "Float" }
    object F64 : Ty() { override fun toString() = "Double" }
    object Unit : Ty() { override fun toString() = "Unit" }
    object Nothing : Ty() { override fun toString() = "Nothing" }
    object Str : Ty() { override fun toString() = "String" }
    /** Opaque C pointer (FILE*, CPointer<*>, char* from getenv). */
    object RawPtr : Ty() { override fun toString() = "CPointer" }
    object Char : Ty() { override fun toString() = "Char" }
    object SB : Ty() { override fun toString() = "StringBuilder" }
    /** ByteArray / IntArray / LongArray / DoubleArray: pointer to { i64 length; elements }, refcount at -8. */
    data class Arr(val elem: Ty) : Ty() { override fun toString() = when (elem) { I8 -> "ByteArray"; I32 -> "IntArray"; I64 -> "LongArray"; F32 -> "FloatArray"; else -> "DoubleArray" } }
    data class MapTy(val k: Ty, val v: Ty) : Ty() { override fun toString() = "HashMap<$k, $v>" }
    data class Iface(val name: String) : Ty() { override fun toString() = name }
    object Pinned : Ty() { override fun toString() = "Pinned<ByteArray>" }
    object Args : Ty() { override fun toString() = "Array<String>" }
    data class Cls(val name: String) : Ty() { override fun toString() = name }
    data class ListTy(val elem: Ty) : Ty() { override fun toString() = "ArrayList<$elem>" }
}

val Ty.llvm: String get() = when (this) {
    Ty.I8 -> "i8"; Ty.I32, Ty.U32, Ty.Char -> "i32"; Ty.I64, Ty.U64 -> "i64"; Ty.I1 -> "i1"; Ty.F32 -> "float"; Ty.F64 -> "double"
    Ty.Unit, Ty.Nothing -> "void"
    else -> "ptr"
}

/** Reference-counted heap types. */
val Ty.isRef: Boolean get() = this is Ty.Cls || this is Ty.ListTy || this is Ty.MapTy || this is Ty.Iface || this == Ty.SB || this == Ty.Str || this is Ty.Arr
/** Ref types whose refcount sits 8 bytes before the pointer (strings point at their chars). */
val Ty.hasPrefixRc: Boolean get() = this == Ty.Str || this is Ty.Arr
val Ty.isNum: Boolean get() = isInt || isFp
val Ty.isFp: Boolean get() = this == Ty.F32 || this == Ty.F64
val Ty.isInt: Boolean get() = this == Ty.I8 || this == Ty.I32 || this == Ty.I64 || this == Ty.U32 || this == Ty.U64
val Ty.isUnsigned: Boolean get() = this == Ty.U32 || this == Ty.U64
val Ty.rank: Int get() = when (this) { Ty.I8 -> 1; Ty.I32, Ty.U32 -> 2; Ty.I64, Ty.U64 -> 3; Ty.F32 -> 4; Ty.F64 -> 5; else -> 0 }
val Ty.size: Int get() = when (this) { Ty.I8 -> 1; Ty.I32, Ty.Char, Ty.F32 -> 4; else -> 8 }
