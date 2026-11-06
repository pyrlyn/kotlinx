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
| `--target <t>`, `--target=<t>` | `native` (default). `js`, `wasm` and `jvm` are accepted but not implemented yet. |
| `--runtime <file.c>` | C runtime to link. Default: `$NEWKOTLIN_RUNTIME`, else `compiler/runtime/runtime.c`. |
| `--emit-llvm` | Also keep `<output>.ll`. |
| `-S` | Write only the LLVM IR to `<output>`; nothing is compiled or linked. |
| `--cc <clang>` | C compiler and linker. Default: `$NK_CC`, Apple clang at `/usr/bin/clang`, the newest mise `conda:clang`, else `clang`. |
| `--cflags "<flags>"` | Optimization flags for clang. Default: `-O3 -flto`. |
| `--opt <path>` | LLVM `opt` run on the IR before linking; see [toolchain.md](toolchain.md) for how it is found. |
| `--no-opt` | Skip the `opt` step. |
| `--leak-check` | Count live runtime objects and report at exit; the program exits 3 on a leak. |
| `--alloc <a>` | `mimalloc` (default), `malloc` or `pool`. |
| `--gc <name>`, `--gc=<name>` | Collector: `epoch` / `perceus` / `incremental` / `rc` (default `$NEWKOTLIN_GC` or `epoch`). Caps from `native/gc/c/<name>/`. |
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
| `NEWKOTLIN_MIMALLOC_DIR` | Where to find `libmimalloc`. |
| `NK_CC`, `NK_OPT` | Default C compiler and `opt`. |

## Exit codes

`newkotlinc` exits 0 on success, 1 on a compile error, a missing tool or an unsupported target,
and 2 on a usage error or an unknown `--gc`.

## Example

```bash
printf 'fun main() {\n    println("Hello from KotlinX")\n}\n' > /tmp/hello.kt
bin/newkotlinc /tmp/hello.kt -o /tmp/hello --gc=rc --emit-llvm
/tmp/hello                     # Hello from KotlinX; /tmp/hello.ll holds the IR
```
