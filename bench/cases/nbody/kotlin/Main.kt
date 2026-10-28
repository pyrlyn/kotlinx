// nbody: 5 bodies, 1M steps of pairwise gravity in Double; checksum = (energy * 1e9).toLong().
@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)
@file:Suppress("DEPRECATION_ERROR")

import kotlin.math.sqrt

class Body(var x: Double, var y: Double, var z: Double, var vx: Double, var vy: Double, var vz: Double, val m: Double)

fun advance(bs: ArrayList<Body>, dt: Double) {
    val n = bs.size
    for (i in 0 until n) {
        val a = bs[i]
        for (j in i + 1 until n) {
            val b = bs[j]
            val dx = a.x - b.x
            val dy = a.y - b.y
            val dz = a.z - b.z
            val d2 = dx * dx + dy * dy + dz * dz
            val mag = dt / (d2 * sqrt(d2))
            a.vx -= dx * b.m * mag
            a.vy -= dy * b.m * mag
            a.vz -= dz * b.m * mag
            b.vx += dx * a.m * mag
            b.vy += dy * a.m * mag
            b.vz += dz * a.m * mag
        }
    }
    for (b in bs) {
        b.x += dt * b.vx
        b.y += dt * b.vy
        b.z += dt * b.vz
    }
}

fun energy(bs: ArrayList<Body>): Double {
    var e = 0.0
    val n = bs.size
    for (i in 0 until n) {
        val a = bs[i]
        e += 0.5 * a.m * (a.vx * a.vx + a.vy * a.vy + a.vz * a.vz)
        for (j in i + 1 until n) {
            val b = bs[j]
            val dx = a.x - b.x
            val dy = a.y - b.y
            val dz = a.z - b.z
            e -= a.m * b.m / sqrt(dx * dx + dy * dy + dz * dz)
        }
    }
    return e
}

fun main() {
    val bs = ArrayList<Body>()
    for (i in 0 until 5) {
        val d = i.toDouble()
        bs.add(Body(d * 3.0, (i * 2 % 5).toDouble() * 1.5, (i % 3).toDouble() - 1.0, 0.01 * d, -0.02 * d, 0.005, 1.0 + d))
    }
    val t0 = kotlin.system.getTimeNanos()
    for (s in 0 until 1_000_000) advance(bs, 0.001)
    val checksum = (energy(bs) * 1e9).toLong()
    val t1 = kotlin.system.getTimeNanos()
    println("checksum=$checksum nanos=${t1 - t0}")
}
