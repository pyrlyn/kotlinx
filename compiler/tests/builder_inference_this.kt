// Builder inference from the receiver flowing into a typed place: `val f: () -> Buildee<Target> = { this }`.
class Target(val n: Int)
class Buildee<TV> { var items = ArrayList<TV>() }
fun <PTV> build(instructions: Buildee<PTV>.() -> Unit): Buildee<PTV> = Buildee<PTV>().apply(instructions)
fun main() {
    var seen: Buildee<Target>? = null
    val b = build {
        val self: () -> Buildee<Target> = { this }
        seen = self()
    }
    b.items.add(Target(3))
    println(seen!!.items.size)
    println(b.items[0].n)
}
