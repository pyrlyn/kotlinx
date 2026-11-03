var order = ""

class C {
    companion {
        val first: String = run { order += "1"; "a" }
        fun both() = first + second + third
    }

    companion object {
        val second: String = run { order += "2"; "b" }
        fun viaObject() = both()
    }

    companion {
        val third: String = run { order += "3"; "c" }
    }
}

class D {
    companion {
        fun ping() = "ping" + D.pong()
    }
    companion {
        fun pong() = "pong"
    }
}

fun main() {
    println(order)
    println(C.third)
    println(order)
    println(C.both())
    println(C.viaObject())
    println(C.Companion.second)
    println(D.ping())
}
