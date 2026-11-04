# KotlinX

KotlinX compiler: a Kotlin -> LLVM native compiler (`newkotlinc`), written in Kotlin (Kotlin/Native),
with a Perceus-style reference-counting runtime. Moved from `listepo/mini-bench` with history.

- `compiler/`: the KotlinX compiler (sources, C runtime, tests, upstream Kotlin tests).
- `bench/`: all benchmarks (Rust / Zig / Kotlin Native / KotlinX compiler), scripts and committed results.
- `bin/newkotlinc`: launcher (builds the compiler on first use).

## Requirements (macOS)

Kotlin/Native `kotlinc-native` 2.4.21 on PATH, Xcode command line tools, `brew install mimalloc`, and `mise install`
in the repo root (LLVM `opt` and matching `clang` from `mise.toml`).

## Quick start

```
./build.sh                                   # -> compiler/build/newkotlinc.kexe
printf 'fun main() {\n    println("Hello from KotlinX")\n}\n' > /tmp/hello.kt
bin/newkotlinc /tmp/hello.kt -o /tmp/hello   # works from any directory
/tmp/hello                                   # Hello from KotlinX
```

## Documentation

- [Usage](docs/usage.md): every `newkotlinc` option, environment variable and exit code.
- [Allocator, LLVM opt and GC](docs/toolchain.md): how mimalloc, `opt` and `clang` are found; GC choices.
- [Testing](docs/testing.md): golden, leak, GC and upstream tests.
- [Benchmarks](docs/benchmarks.md): running and comparing benchmarks, committed results.
- [Architecture](compiler/docs/ARCHITECTURE.md): pipeline, key files, how to add a feature (start here to contribute).
- [GC](compiler/gc/README.md), [STATUS.md](STATUS.md), [bench/table.md](bench/table.md), feature roadmap [#22](https://github.com/pyrlyn/kotlinx/issues/22).
- GC extraction plan (not done yet): [`compiler/gc/docs/extraction-plan.md`](compiler/gc/docs/extraction-plan.md); current state: [`STATUS.md`](STATUS.md#status-2026-11-03).
