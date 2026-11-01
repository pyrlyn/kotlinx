import kotlin.native.internal.*

object Empty {}
object Consts {
    const val A = 1
    const val B = 2.toShort()
    const val S = "s"
}
object WithField { val x = 4 }
object WithInit { init { println("init") } }
object Computed { val x: Int get() = 4 }

fun main() {
    println(Empty.isPermanent())
    println(Consts.isPermanent())
    println(WithField.isPermanent())
    println(WithInit.isPermanent())
    println(Computed.isPermanent())
}
