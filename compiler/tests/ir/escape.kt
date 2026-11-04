// Escape analysis: `val x = C(...)` becomes a stack object (`alloca %C.C`) only when every use keeps it local.
// Checks: `// IR <function>: <ERE>` must match a line of @k.<function>; `// IR-NOT` must match none.
// IR stackFields: alloca %C\.P
// IR stackMethods: alloca %C\.P
// IR stackBranches: alloca %C\.P
// IR-NOT heapReturned: alloca %C\.P
// IR-NOT heapArg: alloca %C\.P
// IR-NOT heapSibling: alloca %C\.P
// IR-NOT heapThis: alloca %C\.P
// IR-NOT heapParamShadow: alloca %C\.P
// IR-NOT heapMethodLambda: alloca %C\.P
// IR-NOT heapCaptured: alloca %C\.P
// IR-NOT heapTemplate: alloca %C\.P
// IR-NOT heapSafe: alloca %C\.P
// IR-NOT heapVar: alloca %C\.P
// IR-NOT heapTyped: alloca %C\.P
// IR-NOT heapRedeclared: alloca %C\.P
// IR-NOT heapCatchName: alloca %C\.P
// IR-NOT heapEq: alloca %C\.P
// IR-NOT heapRefField: alloca %C\.R
class P(var x: Int, val y: Int) {
    fun sum(): Int = x + y
    fun bump(d: Int) { x += d }
    fun viaSibling(): Int = sum()
    fun leakThis(): P = this
    fun shadow(x: Int): Int = x + y
    fun withLam(f: (Int) -> Int): Int = f(x)
}
class R(val s: String)
fun id(p: P): P = p
fun stackFields(): Int { val p = P(1, 2); p.x = 5; return p.x + p.y }
fun stackMethods(): Int { val p = P(1, 2); p.bump(3); return p.sum() }
fun stackBranches(c: Boolean): Int { val p = P(1, 2); var r = 0; if (c) r = p.x else r = p.y; while (r < 10) r += p.x; when (r) { 10 -> r += p.y; else -> r -= p.y }; return r }
fun heapReturned(): P { val p = P(1, 2); return p }
fun heapArg(): Int { val p = P(1, 2); return id(p).x }
fun heapSibling(): Int { val p = P(1, 2); return p.viaSibling() }
fun heapThis(): Int { val p = P(1, 2); return p.leakThis().x }
fun heapParamShadow(): Int { val p = P(1, 2); return p.shadow(4) }
fun heapMethodLambda(): Int { val p = P(1, 2); return p.withLam { it + 1 } }
fun heapCaptured(): Int { val p = P(1, 2); val f = { p.x }; return f() }
fun heapTemplate(): String { val p = P(1, 2); return "v=${p.x} $p" }
fun heapSafe(): Int { val p = P(1, 2); return p?.x ?: 0 }
fun heapVar(): Int { var p = P(1, 2); return p.x }
fun heapTyped(): Int { val p: P = P(1, 2); return p.x }
fun heapRefField(): Int { val r = R("a"); return r.s.length }
fun heapRedeclared(): Int { val p = P(1, 2); for (p in 0 until 2) println(p); return p.x }
fun heapCatchName(): Int { val p = P(1, 2); try { println(p.x) } catch (p: RuntimeException) { println("c") }; return p.y }
fun heapEq(): Boolean { val p = P(1, 2); return p == P(1, 2) }
fun main() {
    println(stackFields()); println(stackMethods()); println(stackBranches(true)); println(heapReturned().x); println(heapArg())
    println(heapSibling()); println(heapThis()); println(heapParamShadow()); println(heapMethodLambda()); println(heapCaptured())
    println(heapTemplate().length > 0); println(heapSafe()); println(heapVar()); println(heapTyped()); println(heapRefField())
    println(heapRedeclared()); println(heapCatchName()); println(heapEq())
}
