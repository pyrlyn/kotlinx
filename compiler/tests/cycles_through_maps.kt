// Reference cycles through HashMap values (String and Int keys) are collected, like those through lists.
class Owner(val name: String) {
    val byName = HashMap<String, Item>()
    val byId = HashMap<Int, Item>()
}

class Item(val owner: Owner, val n: Int)

class Registry { val groups = HashMap<String, Group>() }
class Group(val reg: Registry) { val members = ArrayList<Group>() }

fun main() {
    for (i in 0 until 500) {
        val o = Owner("o" + i)
        o.byName["a" + i] = Item(o, i)
        o.byId[i] = Item(o, i)
        o.byId[i] = Item(o, i + 1)   // overwrite releases the old value
    }
    val keep = Owner("keep")
    keep.byId[1] = Item(keep, 7)
    println(keep.byId[1]!!.n.toString() + " " + keep.byId[1]!!.owner.name)
    // Map -> object -> list -> object -> back to the map's owner.
    for (r in 0 until 100) {
        val reg = Registry()
        val g = Group(reg)
        reg.groups["g"] = g
        g.members.add(Group(reg))
        g.members.add(g)
    }
    println("done")
}
