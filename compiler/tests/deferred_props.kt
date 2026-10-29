// Body properties without initializers: lateinit var and vals assigned in init blocks.
class Node(val v: Int) {
    lateinit var name: String
    val doubled: Int
    var next: Node? = null
    val tags: ArrayList<String>
    init { doubled = v * 2; tags = ArrayList<String>(); tags.add("t" + v) }
    fun setup(): Node { name = "node" + v; return this }
}
fun main() {
    val n = Node(3).setup()
    println(n.name + " " + n.doubled + " " + n.tags)
    n.name = "renamed"
    println(n.name)
}
