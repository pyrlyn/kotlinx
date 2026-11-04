// `when` over a sealed class or interface without `else`, exhaustive through `is` branches.
sealed class Shape
class Circle(val r: Int) : Shape()
class Rect(val w: Int, val h: Int) : Shape()
sealed interface Cmd
class Go(val n: Int) : Cmd
class Stop : Cmd
sealed class Expr { class Num(val v: Int) : Expr(); class Add(val l: Expr, val r: Expr) : Expr() }
fun area(s: Shape) = when (s) { is Circle -> 3 * s.r * s.r; is Rect -> s.w * s.h }
fun run(c: Cmd): String = when (c) { is Go -> "go " + c.n; is Stop -> "stop" }
fun eval(e: Expr): Int = when (e) { is Expr.Num -> e.v; is Expr.Add -> eval(e.l) + eval(e.r) }
fun main() {
    println(area(Circle(2)) + area(Rect(2, 3)))
    println(run(Go(1)) + " " + run(Stop()))
    println(eval(Expr.Add(Expr.Num(2), Expr.Add(Expr.Num(3), Expr.Num(4)))))
}
