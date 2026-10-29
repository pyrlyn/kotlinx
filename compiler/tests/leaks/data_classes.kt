// Data classes: copies sharing and replacing reference properties, == on owned temporaries, destructuring of owned
// temporaries, Pair values in lists, and nested data classes.
data class Name(val first: String, val last: String)
data class Person(val name: Name, val tags: ArrayList<String>, val age: Int)

fun make(i: Int): Person = Person(Name("a" + i, "b"), arrayListOf("t" + i), i)

fun main() {
    var hits = 0
    var acc = 0
    val ps = ArrayList<Person>()
    for (i in 0 until 200) {
        val p = make(i % 7)
        val q = p.copy(age = p.age + 1)
        val r = q.copy(name = Name("x", "y"))
        if (make(i % 7) == p) hits++
        if (r != q) hits++
        val (n, tags, age) = make(i)
        acc += n.first.length + tags.size + age
        val (f, l) = r.name
        acc += f.length + l.length
        acc += p.hashCode() % 3
        ps.add(r)
        val pr = i to make(i)
        acc += pr.first + pr.second.age
        val pairs = listOf(1 to "x", 2 to "y")
        for ((k, v) in pairs) acc += k + v.length
    }
    println(hits)
    println(acc)
    println(ps.size)
    println(ps[3])
}
