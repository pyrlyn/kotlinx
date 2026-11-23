<h1><picture><source media="(prefers-color-scheme: dark)" srcset="brand/logo-dark.svg"><img src="brand/logo.svg" width="32" height="32" alt="" align="absmiddle"></picture> ZKotlin</h1>

ZKotlin compiler: a Kotlin -> LLVM native compiler (`newkotlinc`), written in Kotlin (Kotlin/Native),
with a Perceus-style reference-counting runtime. Moved from `listepo/mini-bench` with history.

- `compiler/`: the ZKotlin compiler (sources, C runtime, tests, upstream Kotlin tests).
- `bench/`: all benchmarks (Rust / Zig / Kotlin Native / ZKotlin compiler), scripts and committed results.
- `bin/newkotlinc`: launcher (builds the compiler on first use).

## Requirements (macOS)

Kotlin/Native `kotlinc-native` 2.4.21 on PATH, Xcode command line tools, `brew install mimalloc`, and `mise install`
in the repo root (LLVM `opt` and matching `clang` from `mise.toml`).

## Quick start

```
./build.sh                                   # -> compiler/build/newkotlinc.kexe
printf 'fun main() {\n    println("Hello from ZKotlin")\n}\n' > /tmp/hello.kt
bin/newkotlinc /tmp/hello.kt -o /tmp/hello   # works from any directory
/tmp/hello                                   # Hello from ZKotlin
```

## Documentation

- [Usage](docs/usage.md): every `newkotlinc` option, environment variable and exit code.
- [Allocator, LLVM opt and GC](docs/toolchain.md): how mimalloc, `opt` and `clang` are found; GC choices.
- [Testing](docs/testing.md): golden, leak, GC and upstream tests.
- [Benchmarks](docs/benchmarks.md): running and comparing benchmarks, committed results.
- [Architecture](compiler/docs/ARCHITECTURE.md): pipeline, key files, how to add a feature (start here to contribute).
- [GC](native/gc/README.md), [STATUS.md](STATUS.md), [bench/table.md](bench/table.md), feature roadmap [#22](https://github.com/pyrlyn/zkotlin/issues/22).
- GC is vendored under [`native/gc/`](native/gc/README.md) (C collectors `@a65a3f6`, Zig port `@2dea030` with ZKotlin patches; default `--gc-runtime=zig`). No git submodule. See that README for Zig 0.17.1 vs legacy 0.16 notes.

## License

ZKotlin is licensed under the [GNU General Public License v3.0](LICENSE) (Copyright 2026 pyrlyn). The vendored collectors
under `native/gc/` come from [pyrlyn/zgc](https://github.com/pyrlyn/zgc), which does not declare a license yet; see
[`native/gc/PROVENANCE.md`](native/gc/PROVENANCE.md#license).
