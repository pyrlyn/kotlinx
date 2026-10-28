# mini-bench results

Time = kernel ns (mean of middle 3 of 5). Peak RSS from `/usr/bin/time -l`. CPU = (user+sys)/wall over N back-to-back launches x5 (includes process startup): N=200, alloc N=20.
Machine: Apple Silicon Mac (arm64), 2026-10-28.

### sum (unchanged, measured earlier)

| Participant | Time, ns | Peak RSS, MB | CPU, % |
|---|---|---|---|
| Rust | 3 733 014 | 1.91 | 75.1 |
| Zig | 5 723 292 | 1.86 | 70.4 |
| Kotlin Native | 5 880 361 | 6.49 | 74.1 |
| New Kotlin | 3 695 583 | 1.73 | 76.1 |

### alloc  (checksums match)

| Participant | Time, ns | Peak RSS, MB | CPU, % |
|---|---|---|---|
| Rust | 165 853 291 | 8.30 | 98.4 |
| Zig | 154 317 930 | 7.78 | 98.1 |
| KotlinNative | 216 380 361 | 39.54 | 112.2 |
| New Kotlin | 52 725 611 | 11.23 | 93.9 |

### recursion  (checksums match)

| Participant | Time, ns | Peak RSS, MB | CPU, % |
|---|---|---|---|
| Rust | 6 069 264 | 1.91 | 83.2 |
| Zig | 6 251 291 | 1.86 | 83.7 |
| KotlinNative | 5 778 722 | 6.47 | 80.3 |
| New Kotlin | 6 173 361 | 1.73 | 78.9 |

### file  (checksums match)

| Participant | Time, ns | Peak RSS, MB | CPU, % |
|---|---|---|---|
| Rust | 81 889 847 | 51.92 | 96.9 |
| Zig | 50 519 138 | 51.92 | 95.1 |
| KotlinNative | 50 840 680 | 56.59 | 94.0 |
| New Kotlin | 51 461 833 | 51.76 | 93.4 |

New Kotlin = `compiler/` (newkotlinc, ours): Kotlin subset -> LLVM IR -> `clang -O3 -flto`, measured 2026-10-28 on the
same Mac; the other rows were not re-run. New Kotlin class instances use a free-list small-object allocator
(Rust/Zig use the system malloc, Kotlin Native its GC heap).

### New Kotlin optimization history

| Case | v1: clang -O2, malloc/free | v2: + free-list allocator, -O3 -flto |
|---|---|---|
| sum | 3 703 694 ns, 1.74 MB, 80.1% | 3 695 583 ns, 1.73 MB, 76.1% |
| alloc | 185 243 611 ns, 12.20 MB, 97.7% | 52 725 611 ns, 11.23 MB, 93.9% |
| recursion | 6 060 028 ns, 1.74 MB, 70.0% | 6 173 361 ns, 1.73 MB, 78.9% |
| file | 51 861 834 ns, 51.78 MB, 91.9% | 51 461 833 ns, 51.76 MB, 93.4% |

v1 was already within 2x of the best row in every case; v2 targets allocation (alloc: 3.5x faster).
Flag sweep (-O2 / -O3 / -O3 -flto / -mcpu=apple-m1) changed sum, recursion and file by < 3% (noise).
