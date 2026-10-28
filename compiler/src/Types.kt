package newkotlin

sealed class Ty {
    object I8 : Ty() { override fun toString() = "Byte" }
    object I32 : Ty() { override fun toString() = "Int" }
    object I64 : Ty() { override fun toString() = "Long" }
    object I1 : Ty() { override fun toString() = "Boolean" }
    object F64 : Ty() { override fun toString() = "Double" }
    object Unit : Ty() { override fun toString() = "Unit" }
    object Nothing : Ty() { override fun toString() = "Nothing" }
    object Str : Ty() { override fun toString() = "String" }
    /** Opaque C pointer (FILE*, CPointer<*>, char* from getenv). */
    object RawPtr : Ty() { override fun toString() = "CPointer" }
    object Bytes : Ty() { override fun toString() = "ByteArray" }
    object Pinned : Ty() { override fun toString() = "Pinned<ByteArray>" }
    object Args : Ty() { override fun toString() = "Array<String>" }
    data class Cls(val name: String) : Ty() { override fun toString() = name }
    data class ListTy(val elem: Ty) : Ty() { override fun toString() = "ArrayList<$elem>" }
}

val Ty.llvm: String get() = when (this) {
    Ty.I8 -> "i8"; Ty.I32 -> "i32"; Ty.I64 -> "i64"; Ty.I1 -> "i1"; Ty.F64 -> "double"
    Ty.Unit, Ty.Nothing -> "void"
    else -> "ptr"
}

/** Reference-counted heap types. */
val Ty.isRef: Boolean get() = this is Ty.Cls || this is Ty.ListTy
val Ty.isNum: Boolean get() = this == Ty.I8 || this == Ty.I32 || this == Ty.I64 || this == Ty.F64
val Ty.isInt: Boolean get() = this == Ty.I8 || this == Ty.I32 || this == Ty.I64
val Ty.rank: Int get() = when (this) { Ty.I8 -> 1; Ty.I32 -> 2; Ty.I64 -> 3; Ty.F64 -> 4; else -> 0 }
