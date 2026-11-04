// Regression: computed `var` properties with get() and set(v) on separate lines (class, object, companion).
class Temp(var celsius: Int) {
    var fahrenheit: Int
        get() = celsius * 9 / 5 + 32
        set(f) { celsius = (f - 32) * 5 / 9 }
    var label: String
        get() = "t" + celsius
        set(v) = println("set " + v)
}
object Counter {
    var raw = 0
    var tens: Int
        get() = raw / 10
        set(v) { raw = v * 10 }
}
class Box {
    companion object {
        var store = 1
        var doubled: Int
            get() = store * 2
            set(v) { store = v / 2 }
    }
}
fun main() {
    val t = Temp(100)
    println(t.fahrenheit)
    t.fahrenheit = 32
    println(t.celsius)
    t.label = "x"
    println(t.label)
    Counter.tens = 4
    println(Counter.raw)
    println(Counter.tens)
    Box.doubled = 10
    println(Box.store)
    println(Box.doubled)
}
