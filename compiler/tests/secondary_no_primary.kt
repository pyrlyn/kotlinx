val log = StringBuilder()

class Point {
    var x: Int
    var y: Int = 7
    var tag = "init"

    init { log.append("init;") }

    constructor(x: Int, y: Int) {
        log.append("xy;")
        this.x = x
        this.y = y
    }

    constructor(s: String) {
        log.append("s;")
        x = s.length
        tag = s
    }

    constructor(d: Double) : this(d.toInt(), 0) {
        log.append("d;")
    }

    fun show() = "$x,$y,$tag"
}

class Empty {
    var n: Int = 1
    constructor(a: Int, b: Int) { n += a * b }
    constructor(k: Int) { n += k }
}

fun local(): String {
    class L {
        val v: Int
        constructor(a: Int) { v = a }
        constructor(b: String) { v = b.length }
        fun r() = "L$v"
    }
    return L(1).r() + L("abc").r()
}

fun main() {
    println(Point(1, 2).show())
    println(Point("hello").show())
    println(Point(3.9).show())
    println(log.toString())
    println(Empty(2, 5).n)
    println(Empty(5).n)
    println(local())
}
