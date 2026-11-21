// Loops nested in main's loops: --target=wasm moves each inner loop into a function of its own
// (compiler/src/backend/native/WasmLoopOutline.kt). Locals the inner loops read and write, labeled break/continue
// to the outer loop, an exception out of an inner loop, and a return from main inside one must behave as before.
class Box(var v: Long)

fun main() {
    val boxes = ArrayList<Box>()
    for (i in 0 until 10) boxes.add(Box(i.toLong()))
    var sum = 0L
    var count = 0
    var last: Box? = null
    for (r in 0 until 50) {
        val k = (r % 4).toLong()
        for (b in boxes) {
            sum += b.v * k
            count++
            last = b
        }
        var j = 0
        while (j < r) { j += 3; sum -= 1 }
    }
    println("sum=$sum count=$count last=${last?.v}")
    var found = -1
    outer@ for (a in 0 until 20) {
        for (b in 0 until 20) {
            if (b > a) continue@outer
            if (a * b == 132) { found = a * 100 + b; break@outer }
        }
    }
    println("found=$found")
    var caught = 0
    for (r in 0 until 5) {
        try {
            for (b in boxes) if (b.v == r.toLong() + 3) throw IllegalStateException("at ${b.v}")
        } catch (e: IllegalStateException) {
            caught++
            println(e.message)
        }
    }
    println("caught=$caught")
    val made = ArrayList<Box>()
    for (r in 0 until 3) {
        for (i in 0 until 4) made.add(Box((r * 10 + i).toLong()))
    }
    println("made=${made.size} ${made[11].v}")
    for (r in 0 until 100) {
        for (i in 0 until 100) {
            if (r * i == 42 * 2) {
                println("return at $r $i")
                return
            }
        }
    }
    println("not reached")
}
