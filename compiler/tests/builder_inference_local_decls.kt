// Builder inference from local functions inside the builder lambda that use its receiver.
class Target(val n: Int)
class Buildee<TV> { val items = ArrayList<TV>() }
fun <PTV> build(instructions: Buildee<PTV>.() -> Unit): Buildee<PTV> = Buildee<PTV>().apply(instructions)
fun main() {
    val a = build {
        fun self(): Buildee<Target> { return this }
        self().items.add(Target(1))
    }
    val b = build {
        fun self(): Buildee<Target> = this
        fun sizeOf(x: Buildee<Target> = this): Int = x.items.size
        self().items.add(Target(2))
        println("size " + sizeOf())
    }
    println(a.items[0].n + b.items[0].n)
    // A plain lambda inside a builder still sees the builder's `this`.
    val c = build {
        val add = { t: Target -> this.items.add(t) }
        val self: () -> Buildee<Target> = { this }
        add(Target(5))
        println(self().items.size)
    }
    println(c.items[0].n)
}
