# Benchmarks

```
cd bench
./build.sh && ./round.sh && RESULTS=results/round ./summarize.sh   # all languages
ONLY=newkotlin ./build.sh && ./run.sh && ./summarize.sh            # KotlinX compiler rows only
./nkbench.sh mytest                                                 # -> bench/results/nk/mytest.txt
```

See `STATUS.md` and `bench/table.md`.

## Tooling (`bench/`)

Run from `bench/` (`cd bench`); the scripts use the in-repo compiler (`../compiler`).

All scripts: warm-up + 5 runs per case, mean of the middle 3, in ms.

- `./nkbench.sh <name> [git-ref]`: KotlinX compiler on every case → `results/nk/<name>.txt`.
  With a ref (for example `main`) the compiler is built from that ref; without one, the working tree is used.
  `NKFLAGS="--alloc malloc" ./nkbench.sh sys` passes extra compiler flags.
- `./allocbench.sh [name]`: system malloc vs jemalloc vs mimalloc, plus Kotlin Native (`bench_kn.kexe`
  from `./build.sh`) in the same session, interleaved → `results/alloc/<name>.txt` (`case variant ms checksum`).
  jemalloc is optional: it is used if found at `$JEMALLOC_DIR` or `$(brew --prefix jemalloc)/lib`.
- `compiler/ab.sh [ref]`: working tree vs a ref, ratio per case.

Results kept in the repo:
- `bench/results/baseline-old-newkotlin.txt`: "old NewKotlin" baseline (main, malloc).
- `bench/results/alloc/sys-je-mi-2026-10-28.txt`: allocator comparison (system / jemalloc / mimalloc).
- `bench/results/alloc/mimalloc-default.txt`, `results/nk/baseline-main-session.txt`: mimalloc-default run with
  K/N and the main baseline in the same session.
