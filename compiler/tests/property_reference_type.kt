class A(val x: Int) { var y = "a" }
var top = 1
val ro = "r"
fun use(f: (A) -> Int, a: A) = f(a)
fun main() {
    val p = A::x
    println(p.get(A(3)) + p(A(4)) + use(p, A(5)))
    println(p.name)
    val q = A::y
    val a = A(1)
    q.set(a, "zz"); println(a.y + q.name)
    val t = ::top
    t.set(9); println(top + t.get()); println(t.name + ::ro.name)
    val b = a::y
    b.set("w"); println(b.get() + b())
}
