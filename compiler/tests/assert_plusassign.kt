// kotlin.test asserts and `+=`/`-=` on mutable lists.
fun main() {
    val xs = mutableListOf<Int>()
    for (i in 0 until 5) xs += i
    val ys = ArrayList<Int>()
    ys += xs
    ys += listOf(9, 8)
    assertEquals(7, ys.size)
    assertTrue(ys[5] == 9)
    assertFalse(ys[0] == 7)
    val s: String? = "a"
    assertNotNull(s)
    assertEquals("a", s)
    assertNotEquals(1, 2)
    println(ys)
}
