// Array API from issue #20: initializers passed as values, spreads mixed with items, emptyArray, toTypedArray,
// references on Array<T> receivers, array printing and data classes with primitive-array properties.

data class P(val xs: IntArray, val ys: Array<Int>?, val cs: CharArray?)

class C(val s: String) {
    fun at(i: Int): Char = s[i]
}

fun letter(i: Int): Char = 'a' + i
val Int.sq: Long get() = this.toLong() * this
val Array<String>.firstElement: String get() = get(0)

fun count(vararg xs: Int): Int = xs.size
fun join(vararg xs: String): String { var s = ""; for (x in xs) s += x; return s }
fun takes(a: Array<String>): Int = a.size

var sideEffects = 0
fun recv(): C { sideEffects++; return C("xyz") }

fun main() {
    // Initializer as a function value: plain, extension-property and bound references, and a lambda argument.
    println(CharArray(3, ::letter).concatToString())
    println(LongArray(4, Int::sq).contentToString())
    println(CharArray(3, recv()::at).concatToString() + " " + sideEffects)
    println(Array(3, { "s$it" }).contentToString())
    val f: (Int) -> Double = { it * 0.5 }
    println(DoubleArray(3, f).contentToString())

    // Spreads mixed with other elements, copied in source order.
    val a = arrayOf("b", "c")
    val l = listOf("a", *a, "d", *a)
    println(l)
    val ints = intArrayOf(2, 3)
    println(intArrayOf(1, *ints, 4).contentToString())
    println(count(1, *ints, *ints))
    println(join("<", *a, ">"))
    val u = uintArrayOf(7u, *uintArrayOf(8u, 9u))
    println(u.contentToString())

    // emptyArray, toTypedArray (a copy), references through Array<T>.
    println(takes(emptyArray()))
    val src = mutableListOf(1, 2)
    val arr = src.toTypedArray()
    src.add(3)
    println("${arr.size} ${arr.contentToString()}")
    val p = Array<String>::firstElement
    println(p.get(arrayOf("first", "second")))

    // toString of lists, chars and booleans.
    println(listOf(1, 2).toString() + " " + mutableListOf("x").toString())
    println('k'.toString() + true.toString())

    // Data classes: arrays compare and hash by identity, print their contents.
    val xs = intArrayOf(1, 2)
    val d = P(xs, arrayOf(3), null)
    println(d)
    println(d == P(xs, d.ys, null))
    println(d == P(intArrayOf(1, 2), d.ys, null))
    println(d.hashCode() == P(xs, d.ys, null).hashCode())

    // fold over primitive arrays, String <-> CharArray.
    println(charArrayOf('O', 'K').fold("") { acc, c -> acc + c })
    println(longArrayOf(1, 2, 3).fold(10L) { acc, x -> acc * x })
    println("hello".toCharArray().reversedArray().concatToString())
    println("123".indexOfAny(charArrayOf('3', '2')))
}
