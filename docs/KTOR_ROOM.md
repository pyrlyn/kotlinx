# What Ktor and Room need from the KotlinX compiler

Analysis for PLAN.md P5 (2026-11-04). KotlinX compiles one Kotlin source file (plus generated preludes) to LLVM IR
for the native target; libraries are source subsets compiled into the program (`Kotlinx.kt`, `Prelude.kt`).
Ktor and Room are large multi-module libraries; neither can be compiled from their own sources today. This lists
the compiler features they depend on, what exists, and a realistic path.

## Demonstrated now (golden tests)
- `compiler/tests/ffi/ffi_http.kt`: an HTTP/1.0 server on BSD sockets through `external fun` + a 40-line C shim
  (`ffi_http.c`): listen on a loopback port, accept, parse the request line and headers, route, respond. Client and
  server run sequentially in one process (deterministic test).
- `compiler/tests/ffi/ffi_sqlite.kt`: SQLite through `external fun` + a shim linked with `-lsqlite3`: in-memory
  database, DDL, prepared statements with bound text/long parameters, rows mapped to a data class, error messages.
- Both run under `--leak-check` in `compiler/test.sh` (C handles are opaque `CPointer`s, never managed by the GC).

## Ktor (server: ktor-server-core + cio, client: ktor-client-core + cio)

| Needed | State | Notes |
|---|---|---|
| Coroutines: `suspend`, structured concurrency, `Job`, cancellation, `withTimeout`, `Dispatchers` | partial | Single-threaded scheduler with virtual time (`Kotlinx.kt`). No real I/O suspension: a socket read blocks the process. Needs an event loop (kqueue/epoll) integrated with the scheduler and `suspendCancellableCoroutine`. |
| `Flow`, `Channel`, `select`, `ByteReadChannel`/`ByteWriteChannel` | partial | Flow/Channel subsets exist; `select` and byte channels do not. |
| Multi-module compilation, `expect`/`actual`, `internal` visibility across modules | missing | Ktor is ~100 Gradle modules with expect/actual per platform. KotlinX compiles one file; needs a module/klib model or a source-concatenation build with name spacing. |
| Generics: variance (`in`/`out`), star projections, reified inline functions, generic class hierarchies | partial | Monomorphization works for most code; star projections (`Foo<*>`), casts to generic types and `open` generic hierarchies with generic methods are rejected (see kt52838, kt66359 in PLAN.md). Ktor's pipeline (`Pipeline<TSubject, TContext>`, `PipelinePhase`, interceptors as `suspend PipelineContext<..>.(..) -> Unit`) relies on all of these. |
| Type-safe builders / DSLs (`routing { get("/") { call.respondText(..) } }`) | mostly | Lambdas with receivers and builder inference work; the DSL itself is plain code. |
| Reflection: `typeOf<T>()`, `KType`, `KClass` | minimal | `TypeInfo` for content negotiation needs `typeOf<T>()` with type arguments. A compile-time `typeOf` (emit a type descriptor per instance) is feasible in a monomorphizing compiler. |
| kotlinx.serialization (content negotiation) | subset | `@Serializable` codegen for classes/enums/lists/nullable/defaults/`@SerialName`; no polymorphism, maps, custom serializers or `SerializersModule`. |
| kotlinx-io / atomicfu / ktor-io | missing | Buffers, `Source`/`Sink`, atomics. Atomics are trivial single-threaded; buffers are plain code once generics are complete. |
| Exceptions with `cause`, `Throwable` hierarchy, `use {}` | partial | `Throwable` typed as `Throwable?` is still missing in some positions (propagateInference test). |
| C interop: sockets, TLS (OpenSSL), DNS | basic | `external fun` with scalars and `CPointer` plus a C shim works (`ffi_http`). No struct layouts, callbacks or `cValue`; shims cover that. |

Path: (1) multi-file compilation with packages and `internal`; (2) star projections and generic class hierarchies;
(3) an I/O event loop under the coroutine scheduler (suspend on EAGAIN, resume from kqueue); (4) a Ktor-like
minimal server API (`embeddedServer`, `routing`, `call.respondText`) written against our runtime as a source
subset, like the kotlinx subsets — compiling upstream Ktor itself is not realistic before (1)-(2).

## Room (KMP Room 2.7+: room-runtime + sqlite-bundled, KSP-generated code)

| Needed | State | Notes |
|---|---|---|
| Annotation processing (KSP) for `@Database`, `@Entity`, `@Dao`, `@Query` | missing | Room generates DAO implementations and SQL validation at build time. Options: run real KSP on the JVM over the sources and compile its output with KotlinX, or a built-in source generator like `withSerializable` (regex/AST-driven) for a subset (`@Entity` data classes, `@Insert`, `@Query` with positional parameters). |
| SQLite driver (`androidx.sqlite` `SQLiteDriver`, `SQLiteConnection`, `SQLiteStatement`) | basic | Shown by `ffi_sqlite.kt` (open, exec, prepare, bind, step, columns, finalize). Wrapping it in the androidx.sqlite interfaces is plain Kotlin. |
| Coroutines: `suspend` DAO methods, `Flow<List<T>>` queries with invalidation | partial | Suspend DAOs work on the scheduler; observable queries need table-invalidation callbacks feeding a Flow (no threads needed). |
| Generics, abstract classes with generated subclasses (`RoomDatabase`, `abstract fun userDao(): UserDao`) | partial | Abstract classes and interfaces work; `Room.databaseBuilder<AppDatabase>()` uses reified generics and a generated `AppDatabase_Impl` constructor (reflection on JVM; a generated factory on native). |
| `expect`/`actual`, multi-module | missing | Same as Ktor. |
| Type converters, nullable columns, enums, `@Embedded`, relations | n/a yet | Generator work once the base exists. |

Path: a built-in `@Entity`/`@Dao` generator for a subset over the SQLite shim is the shortest route to "Room-shaped"
code compiling; real Room needs KSP output plus multi-module compilation.

## Compiler work that unblocks both (priority)
1. Multi-file / multi-module compilation with packages, imports and `internal`.
2. Star projections, casts to generic types, generic `open` hierarchies with generic methods.
3. `expect`/`actual`.
4. Compile-time `typeOf<T>()` / `KClass<T>` with type arguments.
5. Non-blocking I/O integrated with the coroutine scheduler.
6. A source-generator hook (shared by `@Serializable` and an `@Entity`/`@Dao` subset).
