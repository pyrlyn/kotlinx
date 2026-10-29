class Point(val x: Int, val y: Int)
interface Shape { fun area(): Int }
class Sq(val s: Int) : Shape { override fun area(): Int = s * s }
class Box<T>(val v: T)

fun Point.sum(): Int = x + y
fun Point.scaled(k: Int): Point = Point(x * k, this.y * k)
fun Int.double(): Int = this * 2
fun Int.isEvenNum(): Boolean = this % 2 == 0
fun String.shout(): String = this + "!"
fun String.firstChar(): Char = this[0]
fun String.len2(): Int = length * 2
fun Shape.twice(): Int = area() * 2
fun <T> Box<T>.get(): T = v
fun <T> List<T>.second(): T = this[1]
fun List<Int>.total(): Int { var t = 0; for (x in this) t += x; return t }
fun <T> T.self(): T = this
fun Long.plusOne(): Long = this + 1L
fun Char.next(): Char = this + 1

fun main() {
    val p = Point(3, 4)
    println(p.sum())
    println(p.scaled(2).sum())
    println(5.double().double())
    println(7.isEvenNum())
    println("hi".shout().shout())
    println("abc".firstChar())
    println("abc".len2())
    val s: Shape = Sq(3)
    println(s.twice())
    println(Sq(2).twice())
    println(Box("boxed").get())
    println(listOf(1, 2, 3).second())
    println(listOf(1, 2, 3).total())
    println("x".self() + 41.self())
    println(9L.plusOne())
    println('a'.next())
    props()
}

class Rect(val w: Int, val h: Int) {
    val area: Int get() = w * h
    val desc: String
        get() {
            return "Rect " + w + "x" + h
        }
    fun bigger(): Int = area + 1
    fun lazyArea(): () -> Int = { area * 10 }
}
val String.lastChar: Char get() = this[length - 1]
val <T> List<T>.secondLast: T get() = this[size - 2]
val Rect.perimeter: Int get() = 2 * (w + h)

fun props() {
    val r = Rect(3, 5)
    println(r.area.toString() + " " + r.desc + " " + r.bigger() + " " + r.lazyArea()())
    println("kotlin".lastChar)
    println(listOf(7, 8, 9).secondLast)
    println(r.perimeter)
}
