interface A { fun hi(): String }

fun make() = (object : A { override fun hi() = "cast" }) as A
fun wrap() = listOf(object : A { override fun hi() = "listed" })

fun main() {
    println(make().hi())
    println(wrap()[0].hi())
}
