// Property types that need methods or globals declared later, and constants read before their declaration.

object Greeting {
    val text = "O${suffix()}"
    fun suffix() = letter
    const val letter = "K"
}

class Pair2 {
    val first = twice()          // runs before `second` is initialized: 0
    fun twice() = second * 2
    val second = 21
}

var touched = false
class Touch {
    val tag = run { touched = true; "t" }
}

fun main() {
    println(Greeting.text)
    val p = Pair2()
    println(p.first.toString() + " " + p.second)
    println(Touch().tag + " " + touched)
}
