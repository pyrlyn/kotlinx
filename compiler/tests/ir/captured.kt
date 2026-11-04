// boxCapturedVars: a `var` is boxed in a `$Ref` only when a closure assigns it, or reads it while it is reassigned.
// IR mk: \$Ref\$Int
// IR counter: \$Ref\$Int
// IR inBranches: \$Ref\$Int
// IR nested: \$Ref\$Int
// IR-NOT inlineOnly: \$Ref
// IR-NOT shadowParam: \$Ref
// IR-NOT shadowIt: \$Ref
// IR-NOT localVal: \$Ref
// IR-NOT readOnly: \$Ref
fun call(f: () -> Unit) = f()
fun mk(): () -> Int { var n = 1; val g = { n * 10 }; n = 5; return g }
fun counter(): () -> Int { var c = 0; return { c++; c } }
fun inlineOnly(): Int { var s = 0; listOf(1, 2, 3).forEach { s += it }; return s }
fun shadowParam(): Int { var x = 1; val f = { x: Int -> x + 100 }; x = 2; return f(x) }
fun shadowIt(): Int { var it = 7; val f: (Int) -> Int = { it * 2 }; it = 8; return f(3) + it }
fun inBranches(b: Boolean): Int { var r = 0; if (b) call { r += 1 } else call { r += 2 }; return r }
fun nested(): Int { var a = 1; call { call { a += 2 } }; return a }
fun localVal(): Int { var v = 3; call { val v = 9; println(v) }; return v }
fun readOnly(): Int { var w = 3; val f = { w + 1 }; return f() }
fun main() { println(mk()()); println(counter()()); println(inlineOnly() + shadowParam() + shadowIt() + inBranches(true) + nested() + localVal() + readOnly()) }
