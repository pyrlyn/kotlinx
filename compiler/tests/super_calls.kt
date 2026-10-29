open class Base(val tag: String) {
    open fun hello(x: Int = 1): String = "Base.hello($x) $tag"
    open fun name(): String = "base"
    open val size: Int get() = 10
}

open class Mid(tag: String) : Base(tag) {
    override fun hello(x: Int): String = "Mid[" + super.hello(x) + "]"
    override val size: Int get() = super.size + 1
}

class Leaf : Mid("leaf") {
    override fun hello(x: Int): String = "Leaf{" + super.hello(x * 2) + "}"
    override fun name(): String = super.name() + "/leaf " + super.tag
    override val size: Int get() = super.size * 2
}

fun main() {
    val b: Base = Leaf()
    println(b.hello(3))
    println(b.hello())
    println(b.name())
    println(b.size)
    println(Mid("m").hello(5))
}
