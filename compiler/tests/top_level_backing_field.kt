val log = StringBuilder()

var counter: Int = 0
    get() {
        log.append("get $field;")
        return field
    }
    set(value) {
        log.append("set $value;")
        field = value
    }

var name: String = "a"
    private set

val doubled: Int = 21
    get() = field * 2

var clamped: Long = 5L
    set(v) { field = if (v > 10L) 10L else v }

fun rename(s: String) { name = s }

class Box(val p: Int) {
    var next = this
    operator fun inc(): Box = Box(p + 1)
}

var reads = 0
var current: Box = Box(-1)
    get() { reads++; return field }

fun main() {
    counter = 3
    counter += 1
    println(counter)
    println(log.toString())
    rename("b")
    println(name)
    println(doubled)
    clamped = 42L
    println(clamped)
    clamped = 7L
    println(clamped)
    current = Box(1)
    ++(current.next)
    println(reads)
    println("${current.p} ${current.next.p}")
}
