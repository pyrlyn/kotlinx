// SQLite helpers for ffi_sqlite.kt (links -lsqlite3, see ffi_sqlite.link): handles are opaque pointers.
#include <sqlite3.h>
#include <stdint.h>

void *kx_db_open(const char *path) { sqlite3 *db = 0; if (sqlite3_open(path, &db) != SQLITE_OK) { sqlite3_close(db); return 0; } return db; }
void kx_db_close(void *db) { sqlite3_close((sqlite3 *)db); }
int32_t kx_db_exec(void *db, const char *sql) { return sqlite3_exec((sqlite3 *)db, sql, 0, 0, 0); }
const char *kx_db_errmsg(void *db) { return sqlite3_errmsg((sqlite3 *)db); }
void *kx_db_prepare(void *db, const char *sql) { sqlite3_stmt *s = 0; if (sqlite3_prepare_v2((sqlite3 *)db, sql, -1, &s, 0) != SQLITE_OK) return 0; return s; }
int32_t kx_stmt_bind_text(void *s, int32_t i, const char *v) { return sqlite3_bind_text((sqlite3_stmt *)s, i, v, -1, SQLITE_TRANSIENT); }
int32_t kx_stmt_bind_long(void *s, int32_t i, int64_t v) { return sqlite3_bind_int64((sqlite3_stmt *)s, i, v); }
/* 1: a row is ready, 0: done, -1: error. */
int32_t kx_stmt_step(void *s) { int r = sqlite3_step((sqlite3_stmt *)s); return r == SQLITE_ROW ? 1 : r == SQLITE_DONE ? 0 : -1; }
int64_t kx_stmt_long(void *s, int32_t col) { return sqlite3_column_int64((sqlite3_stmt *)s, col); }
const char *kx_stmt_text(void *s, int32_t col) { const unsigned char *t = sqlite3_column_text((sqlite3_stmt *)s, col); return t ? (const char *)t : ""; }
void kx_stmt_reset(void *s) { sqlite3_reset((sqlite3_stmt *)s); sqlite3_clear_bindings((sqlite3_stmt *)s); }
void kx_stmt_finalize(void *s) { sqlite3_finalize((sqlite3_stmt *)s); }
