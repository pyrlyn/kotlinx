// `a[i, j]++` / `++a[i, j]` as expressions, `operator fun set(vararg ...)`, vararg constructor properties and
// collection-literal parameter defaults.
var log = ""
fun logged(s: String): String { log += s; return s }

object Cube {
    var x = 0
    operator fun get(i: Int, j: Int, k: Int): Int { log += "get;"; return x + i + j + k - 6 }
    operator fun set(i: Int, j: Int, k: Int, v: Int) { log += "set;"; x = v }
}

fun join(xs: List<String>): String { var r = ""; for (x in xs) r += if (r.isEmpty()) x else "+$x"; return r }

class Tags {
    var last = ""
    operator fun get(vararg keys: String): String = join(keys) + "=" + last
    operator fun set(vararg keys: String, value: String) { last = join(keys) + ":" + value }
}

class Pack(val k: Int, vararg val xs: String = [])
fun sum(xs: List<Int> = [], ys: IntArray = []): Int { var s = xs.sum(); for (y in ys) s += y; return s }

fun main() {
    Cube.x = 0
    val old = Cube[1, 2, 3]++
    val new = ++Cube[1, 2, 3]
    println("$old $new ${Cube.x} $log")
    val t = Tags()
    t[logged("a"), logged("b")] = logged("v")
    println("${t.last} $log")
    println(t["p", "q"])
    val p = Pack(1, "O", "K")
    println("${p.xs.size} ${p.xs[0]}${p.xs[1]} ${Pack(2).xs.size}")
    println("${sum()} ${sum(listOf(1, 2))} ${sum(ys = intArrayOf(4))}")
}
