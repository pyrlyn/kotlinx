class Plain

companion fun Plain.make(s: String) = "made:$s"
companion val Plain.tag: String = "T"
companion var Plain.counter: Int = 0

companion fun Plain.bump(): Int {
    counter += 1
    return counter
}

class Holder(val v: Int) {
    companion object {
        val base = 10
    }
}

companion fun Holder.of(x: Int): Holder = Holder(base + x)
companion const val Holder.LIMIT = 99

class WithBlock {
    companion {
        val inner = "in"
    }
}

companion fun WithBlock.both() = inner + "+ext"

fun main() {
    println(Plain.make("a"))
    println(Plain.tag)
    Plain.bump()
    println(Plain.bump())
    println(Holder.of(5).v)
    println(Holder.LIMIT)
    println(WithBlock.both())
}
