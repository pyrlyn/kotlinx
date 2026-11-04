// HashMap with reference values (String, classes, lists): the map owns its values; overwrite releases the old one.
class P(val name: String, var n: Int)
fun main() {
    val names = HashMap<Int, String>()
    names[1] = "one"
    names[2] = "t" + "wo"
    val keep = names[2]
    names[2] = "zwei"
    println(keep)
    println(names[2])
    println(names.size)
    val ps = HashMap<String, P>()
    for (i in 0 until 100) ps["p" + i] = P("n" + i, i)
    ps["p5"] = P("five", 5)
    val p = ps["p7"]!!
    p.n += 1
    println(ps["p7"]!!.n)
    println(ps["p5"]!!.name)
    println(ps.containsKey("p99"))
    val lists = hashMapOf<String, ArrayList<Int>>()
    lists.put("a", ArrayList<Int>())
    lists["a"]!!.add(3)
    lists["a"]!!.add(4)
    println(lists["a"]!!.size)
    lists.put("a", ArrayList<Int>())
    println(lists["a"]!!.size)
}
