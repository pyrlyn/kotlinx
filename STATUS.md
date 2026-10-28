# STATUS

## Rows
- Rust (rustc/cargo, opt-level 3), Zig 0.16 (ReleaseFast), Kotlin Native 2.4.21 (`-opt`).
- New Kotlin (Kotlin -> LLVM, ours): **pending** — the compiler does not exist yet. The same `Main.kt` sources are what it must compile.
- `zigjit/` is the old ZigJit participant, unused now (kept; deleting needs Ivan's yes).

## Cases (every program prints `checksum=` / `sum=` and `nanos=` for the timed kernel)
| Case | Sources | Kernel | Checksum |
|---|---|---|---|
| sum | `rust/`, `zig/`, `kotlin/` | 1..10M, +i² if i%3==0 else -i² | -430663335525468082 |
| alloc | `cases/alloc/*` | 50 rounds × 200k boxed `P(x,y)` in a list, summed | 31012435000000 |
| recursion | `cases/recursion/*` | naive fib(32) + non-tail `deep(100_000)` | 5002228309 |
| file | `cases/file/*` | read `data/words.txt` (~50 MB, `gen_data.py`), count lines/words, h=h*31+byte | 3412301833168394113 |

## Measured
Kernel time ns (in-process clock), peak RSS (`/usr/bin/time -l`), CPU % = (user+sys)/wall over 200 launches ×5 (alloc: 20). Mean of middle 3 of 5. See `table.md`.

## Resume
```
./build.sh                         # all cases; generates data/ if missing
ALL=1 CASES="alloc recursion file" ./run.sh && ./summarize.sh
# when the Kotlin->LLVM compiler exists:
KLLVM_COMPILER=/path/to/compiler ./build.sh   # -> kllvm/<case>, kllvm/bench_kllvm (adjust CLI in build.sh)
./run.sh && ./summarize.sh                    # KotlinLLVM rows only
```
Then replace the `pending` rows in `table.md`.
