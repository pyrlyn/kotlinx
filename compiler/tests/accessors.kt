class Temp {
    var celsius: Int = 0
        set(value) { field = if (value < -273) -273 else value }
    var log: String = ""
        get() = "[" + field + "]"
        set(v) { field = field + v }
    var hits: Int = 0
        private set
    val doubled: Int = 21
        get() = field * 2
    fun bump() { hits++; celsius += 1 }
}

fun main() {
    val t = Temp()
    t.celsius = -500
    println(t.celsius)
    t.bump(); t.bump()
    println("${t.celsius} ${t.hits}")
    t.log = "a"; t.log = "b"
    println(t.log)
    println(t.doubled)
    t.celsius = 30
    t.celsius += 5
    println(t.celsius)
}
