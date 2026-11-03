class MyList(val data: List<String>) {
    companion object {
        operator fun of(vararg strs: String) = MyList(strs)
    }
}

fun join(l: MyList): String { var r = ""; for (e in l.data) r += e; return r }
fun total(xs: IntArray): Int { var t = 0; for (x in xs) t += x; return t }
fun names(): List<String> = ["a", "b"]
fun grid(): List<List<Int>> {
    return [[1], [2, 3]]
}

val top: Array<String> = ["t"]

fun main() {
    val a: IntArray = [1, 2, 3]
    val e: List<Int> = []
    val m: MutableList<Int> = [4]
    m.add(5)
    println("${a.size} ${e.size} $m ${names()} ${grid()} ${top[0]}")
    println(total([10, 20]))
    println(join(["O", "", "K"]))
    val cs: CharArray = ['x', 'y']
    println(cs[1])
}
