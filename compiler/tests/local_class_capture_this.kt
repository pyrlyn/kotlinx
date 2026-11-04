class Target { override fun toString() = "Target" }
class Box<T> { var v: T? = null }
interface IBox<T>
fun <P> build(f: Box<P>.() -> Unit): Box<P> = Box<P>().apply(f)
fun <P> ibuild(f: IBox<P>.() -> Unit) { (object : IBox<P> {}).let { f(it) } }

fun main() {
    val b = build {
        class Getter { val src: Box<Target> get() = this@build }
        class Field { val src: Box<Target> = this@build }
        this.v = Target()
        println(Getter().src.v)
        println(Field().src === this)
    }
    println(b.v)
    ibuild {
        class C { val x: IBox<Target> = this@ibuild }
        println(C().x === this)
    }
}
