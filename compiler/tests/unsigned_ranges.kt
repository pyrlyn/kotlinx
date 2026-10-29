// Unsigned ranges and progressions: UInt/ULong .., until, downTo, step, in, count, toString.
fun main() {
    val l = mutableListOf<UInt>()
    for (i in 4u downTo 1u step 1 step 1) l += i
    println(l)
    for (i in 1u..10u step 3) print("" + i + " ")
    println()
    for (i in 0u until 0u) println("never")
    val r = 3u..9u
    println(r)
    println(5u in r)
    println(10u in r)
    println((10uL downTo 1uL step 4L))
    var s = 0uL
    for (x in 1uL..4uL) s += x
    println(s)
    println(4000000000u in 1u..4000000001u)
    println((1u..10u step 3).count())
}
