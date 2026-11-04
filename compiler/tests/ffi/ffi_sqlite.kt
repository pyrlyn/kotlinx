// SQLite through `external fun` (ffi_sqlite.c, linked with -lsqlite3): a small DAO over an in-memory database,
// prepared statements with bound parameters, rows read back into Kotlin classes.
external fun kx_db_open(path: CPointer): CPointer
external fun kx_db_close(db: CPointer): Unit
external fun kx_db_exec(db: CPointer, sql: CPointer): Int
external fun kx_db_errmsg(db: CPointer): CPointer
external fun kx_db_prepare(db: CPointer, sql: CPointer): CPointer
external fun kx_stmt_bind_text(s: CPointer, i: Int, v: CPointer): Int
external fun kx_stmt_bind_long(s: CPointer, i: Int, v: Long): Int
external fun kx_stmt_step(s: CPointer): Int
external fun kx_stmt_long(s: CPointer, col: Int): Long
external fun kx_stmt_text(s: CPointer, col: Int): CPointer
external fun kx_stmt_reset(s: CPointer): Unit
external fun kx_stmt_finalize(s: CPointer): Unit

/** A NUL-terminated copy of [s] for C. */
fun cstr(s: String): ByteArray { val b = s.encodeToByteArray(); val r = ByteArray(b.size + 1); for (i in 0 until b.size) r[i] = b[i]; return r }

data class User(val id: Long, val name: String, val age: Long)

class Db(path: String) {
    val handle: CPointer = cstr(path).usePinned { kx_db_open(it.addressOf(0)) }
    fun exec(sql: String) {
        val rc = cstr(sql).usePinned { kx_db_exec(handle, it.addressOf(0)) }
        if (rc != 0) throw IllegalStateException("sqlite: " + kx_db_errmsg(handle).toKString())
    }
    fun prepare(sql: String): CPointer = cstr(sql).usePinned { kx_db_prepare(handle, it.addressOf(0)) }
    fun close() { kx_db_close(handle) }
}

class UserDao(val db: Db) {
    val insert: CPointer = db.prepare("INSERT INTO users(name, age) VALUES (?, ?)")
    val byAge: CPointer = db.prepare("SELECT id, name, age FROM users WHERE age >= ? ORDER BY age, name")
    fun add(name: String, age: Long) {
        cstr(name).usePinned { kx_stmt_bind_text(insert, 1, it.addressOf(0)) }
        kx_stmt_bind_long(insert, 2, age)
        if (kx_stmt_step(insert) != 0) throw IllegalStateException("insert failed")
        kx_stmt_reset(insert)
    }
    fun olderThan(age: Long): List<User> {
        val r = ArrayList<User>()
        kx_stmt_bind_long(byAge, 1, age)
        while (kx_stmt_step(byAge) == 1) r.add(User(kx_stmt_long(byAge, 0), kx_stmt_text(byAge, 1).toKString(), kx_stmt_long(byAge, 2)))
        kx_stmt_reset(byAge)
        return r
    }
    fun close() { kx_stmt_finalize(insert); kx_stmt_finalize(byAge) }
}

fun main() {
    val db = Db(":memory:")
    db.exec("CREATE TABLE users(id INTEGER PRIMARY KEY, name TEXT NOT NULL, age INTEGER)")
    val dao = UserDao(db)
    dao.add("Ann", 31L); dao.add("Bo", 17L); dao.add("Cy", 45L); dao.add("Dee 'quoted'", 31L)
    for (u in dao.olderThan(18L)) println(u)
    println(dao.olderThan(40L).size)
    try { db.exec("SELECT * FROM nope") } catch (e: IllegalStateException) { println(e.message) }
    dao.close()
    db.close()
}
