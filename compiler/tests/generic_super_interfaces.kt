// Interfaces extending generic interfaces: `interface B : A<String>`, `interface I2<T> : I<T>`.
interface A<T> { fun get(): T }
interface B : A<String> { fun twice(): String }
class Impl : B {
    override fun get() = "x"
    override fun twice() = get() + get()
}

interface I<T> { fun put(x: T): Int }
interface I2<T> : I<T> { fun size(): Int }
class Store : I2<Int> {
    var sum = 0
    override fun put(x: Int): Int { sum += x; return sum }
    override fun size() = sum
}

interface Pair2<K, V> { fun key(): K; fun value(): V }
interface Named<V> : Pair2<String, V>
class Entry(val v: Int) : Named<Int> {
    override fun key() = "k"
    override fun value() = v
}

fun useA(a: A<String>) = a.get()
fun useI(i: I<Int>) = i.put(5)
fun <V> describe(p: Pair2<String, V>) = p.key() + "=" + p.value()

fun main() {
    val b: B = Impl()
    println(b.twice() + " " + b.get() + " " + useA(b))
    val s = Store()
    useI(s)
    val i2: I2<Int> = s
    println("${i2.put(2)} ${i2.size()}")
    println(describe(Entry(7)))
}
