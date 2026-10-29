// Default and named arguments: reference-typed defaults (fresh objects and strings), named arguments moved out of
// order, defaults referring to receiver members, and default lambdas.
class Buf(val items: ArrayList<String> = arrayListOf("init"), val name: String = "buf") {
    fun add(s: String = name + "!", times: Int = 1): Buf { for (i in 0 until times) items.add(s); return this }
}
fun join(xs: List<String>, sep: String = ",", pre: String = "<"): String { val sb = StringBuilder(pre); for (x in xs) { sb.append(x); sb.append(sep) }; return sb.toString() }
fun mapAll(xs: ArrayList<String>, f: (String) -> String = { it + it }): ArrayList<String> { val r = ArrayList<String>(); for (x in xs) r.add(f(x)); return r }

fun main() {
    var acc = 0
    for (i in 0 until 200) {
        val b = Buf()
        b.add().add("x" + i, times = 2)
        val c = Buf(name = "n" + i).add(times = 3)
        acc += b.items.size + c.items.size
        acc += join(b.items).length + join(sep = ";", xs = c.items).length
        acc += mapAll(b.items).size + mapAll(c.items) { it + i }.size
    }
    println(acc)
}
