// lateinit properties of companion objects and lifted classes throw when read before assignment.
class A {
    fun f(): String { try { val a = str; return "no throw" } catch (e: RuntimeException) { return "threw" } }
    fun g(): String { try { val a = str; println(a); return "no throw" } catch (e: RuntimeException) { return "threw" } }
    companion object { lateinit var str: String
      fun h(): String { try { val a = str; return "no throw" } catch (e: RuntimeException) { return "threw" } } }
}
fun main() { println(A().f()); println(A().g()); println(A.h()) }
