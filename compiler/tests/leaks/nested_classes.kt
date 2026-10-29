// Inner classes: instances holding their outer instance (created inside and outside the outer class), outer
// instances outliving and dying before their inner instances, and nested classes in collections.
class Tree(val label: String) {
    val kids = ArrayList<String>()
    class Leaf(val v: String)
    inner class Cursor(var pos: Int) {
        fun cur(): String = if (pos < kids.size) kids[pos] else label
        fun next(): Cursor = Cursor(pos + 1)
    }
    fun start(): Cursor = Cursor(0)
}

fun mk(i: Int): Tree.Cursor { val t = Tree("t" + i); t.kids.add("k" + i); return t.start() }

fun main() {
    var acc = 0
    val leaves = ArrayList<Tree.Leaf>()
    for (i in 0 until 300) {
        val t = Tree("x" + i)
        t.kids.add("a"); t.kids.add("bb")
        var c = t.start()
        while (c.pos < 3) { acc += c.cur().length; c = c.next() }
        val d = mk(i).next()
        acc += d.cur().length
        val e = t.Cursor(1)
        acc += e.cur().length
        leaves.add(Tree.Leaf("l" + i))
    }
    println(acc)
    println(leaves.size)
    println(leaves[7].v)
}
