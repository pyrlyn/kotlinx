// Delegated properties: untyped delegated vars, property references and constants as delegates, extension
// properties, provideDelegate, delegated locals.

class Counter(var n: Int) {
    var reads = 0
    operator fun getValue(thisRef: Any?, p: Any?): Int { reads++; return n }
    operator fun setValue(thisRef: Any?, p: Any?, v: Int) { n = v }
}

class Holder {
    var count by Counter(1)          // untyped delegated var: the setter takes getValue's type
    var title: String = "t"
    var alias by ::title             // bound property reference as a delegate
}

var topCount by Counter(10)
var topName = "top"
val nameView by ::topName
var nameAlias by ::topName

class Box(var item: String)
class Shelf(val box: Box) {
    var label by box::item           // reference bound to another object
}

// Extension properties: one shared delegate, the receiver is `thisRef`.
class Suffix(val s: String) {
    operator fun getValue(thisRef: String, p: Any?): String = thisRef + s
}
val String.excited by Suffix("!")
val String.asked by Suffix("?")

class Greeter(val greeting: String) {
    val String.greeted: String by ::greeting
    fun greet(name: String) = name.greeted + " " + name
}

// provideDelegate picks the delegate when the property is created.
class Fixed(val v: String) {
    operator fun getValue(thisRef: Any?, p: Any?) = v
}
class Factory(val prefix: String) {
    operator fun provideDelegate(thisRef: Any?, p: Any?) = Fixed(prefix + (if (thisRef == null) "top" else "member"))
}
val provided by Factory("p-")
class UsesFactory {
    val provided by Factory("p-")
}

// A constant as a delegate through an extension getValue.
operator fun Int.getValue(thisRef: Any?, p: Any?): String = "const " + this
val fromConst: String by 42

fun main() {
    val h = Holder()
    println(h.count)
    h.count = 7
    println(h.count)
    h.alias = "renamed"
    println(h.title)

    println(topCount)
    topCount += 5
    println(topCount)
    nameAlias = "changed"
    println(nameView + " " + topName)

    val shelf = Shelf(Box("old"))
    shelf.label = "new"
    println(shelf.box.item)

    println("wow".excited + " " + "what".asked)
    println(Greeter("hi").greet("bob"))

    println(provided + " " + UsesFactory().provided)
    println(fromConst)

    val lazyLocal by lazy { "lazy " + topName }
    var localCount by Counter(3)
    localCount = localCount * 2
    println(lazyLocal + " " + localCount)
}
