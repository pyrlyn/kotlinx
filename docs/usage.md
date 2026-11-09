# Using `newkotlinc`

```
newkotlinc <input.kt> -o <output> [options]
```

`bin/newkotlinc` builds `compiler/build/newkotlinc.kexe` on first use, sets `NEWKOTLIN_RUNTIME` to
the in-repo runtime, and uses the `opt` pinned by the repo's `mise.toml` unless `NK_OPT` is set.

## Options

| Option | Meaning |
| --- | --- |
| `-o <output>` | Output binary (or IR file with `-S`). Required. |
| `--target <t>`, `--target=<t>` | `native` (default), `wasm`: the same LLVM IR for wasm32-wasi, linked into a `.wasm` module (see [Wasm target](#wasm-target)), or `js`: one JavaScript file for node (see [js-target.md](js-target.md)). `jvm` is accepted but not implemented yet. |
| `--runtime <file.c>` | C runtime to link. Default: `$NEWKOTLIN_RUNTIME`, else `compiler/runtime/runtime.c`. |
| `--emit-llvm` | Also keep `<output>.ll`. |
| `-S` | Write only the LLVM IR to `<output>`; nothing is compiled or linked. |
| `--cc <clang>` | C compiler and linker. Default: `$NK_CC`, Apple clang at `/usr/bin/clang`, the newest mise `conda:clang`, else `clang`. |
| `--cflags "<flags>"` | Optimization flags for clang. Default: `-O3 -flto`. |
| `--opt <path>` | LLVM `opt` run on the IR before linking; see [toolchain.md](toolchain.md) for how it is found. |
| `--no-opt` | Skip the `opt` step. |
| `--leak-check` | Count live runtime objects and report at exit; the program exits 3 on a leak. |
| `--alloc <a>` | `mimalloc` (default), `malloc` or `pool`. |
| `--gc <name>`, `--gc=<name>` | Collector: `epoch` / `perceus` / `incremental` / `compact` / `rc` (default `$NEWKOTLIN_GC` or `epoch`; `compact` for `--target=js`, which has no threads for `epoch`). Caps from `native/gc/c/<name>/`; `compact` exists only in Zig (`--gc-runtime=zig`). |
| `--gc-runtime <zig\|c>` | Collector backend (default `$NEWKOTLIN_GC_RUNTIME` or `zig`). `zig` links `libgc-<name>.a`; `c` compiles `native/gc/c/<name>/gc.c`. Shared `runtime.c` always. |
| `-h`, `--help` | Print the usage text. |

## Environment

| Variable | Used for |
| --- | --- |
| `NEWKOTLIN_RUNTIME` | Default `--runtime`. |
| `NEWKOTLIN_GC` | Default `--gc`. |
| `NEWKOTLIN_GC_DIR` | Caps / `kxgc.h` / C `gc.c` tree (default: `native/gc/c`). |
| `NEWKOTLIN_GC_RUNTIME` | `zig` (default) or `c` — collector backend language. |
| `NEWKOTLIN_GC_ZIG_LIB_DIR` | Directory with `libgc-<name>.a` (default: `native/gc/zig/zig-out/lib`). |
| `NEWKOTLIN_GC_ZIG_WASM_LIB_DIR` | Same for `--target=wasm` and the `libgc-<name>.bc` of `--target=js` (default: `native/gc/zig/zig-out/wasm32-wasi/lib`). |
| `NEWKOTLIN_MIMALLOC_DIR` | Where to find `libmimalloc`. |
| `NK_CC`, `NK_OPT` | Default C compiler and `opt`. |
| `NK_WASM_CC` | C compiler and linker for `--target=wasm`, C compiler for `--target=js` (default: `zig cc -target wasm32-wasi`). |

## Wasm target

`--target=wasm` reuses the whole native pipeline: the frontend, `NativeBackend` (with `LlvmTarget.WASM32`:
wasm32 triple and data layout, 32-bit `size_t`/`long` for libc calls, entry `__main_argc_argv`), `opt`,
`runtime.c` and a pyrlyn/gc collector. Objects live in linear memory and are managed by the collector, not by the
engine's GC. Defaults and limits:

- `--gc perceus` (synchronous cycle collection; `incremental` and `rc` also work; `epoch` needs threads),
  `--alloc malloc` (wasi-libc) or `pool`; no `--link`.
- `--gc-runtime=zig` (default) links the Zig collectors built for wasm32-wasi:
  `native/gc/zig/zig-out/wasm32-wasi/lib/libgc-<name>.a` (or `$NEWKOTLIN_GC_ZIG_WASM_LIB_DIR`), a separate prefix
  so the host archives in `zig-out/lib` stay intact. `bin/newkotlinc --target=wasm` rebuilds them like the host
  ones; `bin/newkotlinc --gc-libs-only --target=wasm [--gc ...] [--alloc ...]` only builds them. Direct
  `newkotlinc.kexe` needs them built first. `--gc-runtime=c` compiles `native/gc/c/<name>/gc.c` instead.
- Linker: `zig cc -target wasm32-wasi` (clang 21 with wasi-libc and wasm-ld), so `opt` must be LLVM 21 or older;
  links with `-s -Wl,-z,stack-size=8388608`.
- Run with any WASI preview1 host, e.g. `node bench/wasm/run.mjs main.wasm` (preopens the current directory).

Golden tests on wasm: `compiler/tests/wasm/run.sh` (233 of 234 pass; `coroutine_cps_core` fails because
`kxCoStart` does not keep its function value alive, a use-after-free that also crashes native builds with
`--alloc malloc --gc=perceus`).

Benchmarks against JetBrains Kotlin/Wasm: `bench/wasm/build.sh && bench/wasm/run.py` (results in
`bench/results/wasm/table.md`).

## Exit codes

`newkotlinc` exits 0 on success, 1 on a compile error, a missing tool or an unsupported target,
and 2 on a usage error or an unknown `--gc`.

## Example

```bash
printf 'fun main() {\n    println("Hello from KotlinX")\n}\n' > /tmp/hello.kt
bin/newkotlinc /tmp/hello.kt -o /tmp/hello --gc=rc --emit-llvm
/tmp/hello                     # Hello from KotlinX; /tmp/hello.ll holds the IR
```
