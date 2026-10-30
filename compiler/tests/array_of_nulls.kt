// arrayOfNulls<T>(n) for reference element types.
class A(val s: String)
fun main() {
  val a = arrayOfNulls<String>(3)
  a[1] = "x"
  println(a[0] == null)
  println(a[1])
  val b = arrayOfNulls<A>(2); b[0] = A("q"); println(b[0]!!.s); println(b.size)
}
