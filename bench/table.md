# Results — equal-conditions final round

## Conditions
- Machine: Apple M3 Max (16 cores, 64 GB), macOS 27.0.1. Run 2026-10-28 20:04 EET.
- **Load average was high** (other agents' jobs running on the Mac): 27.1 at start, 12.7 at end
  (per-case values in `results/round/conditions.txt`). Absolute numbers may be inflated; relative order within a case is fair because all rows ran interleaved.
- Per case: file cache warmed (`cat data/words.txt`), one warm-up run per row, then 5 runs **round-robin**
  (Rust, Zig, KotlinNative, NewKotlin, NewKotlinPool), then 5 round-robin CPU batches.
- Time = mean of the middle 3 of 5 (sorted) self-reported ns; Peak RSS from `/usr/bin/time -l`;
  CPU % = (user+sys)/wall over a batch of N back-to-back launches (N sized to ~2 s).
- Same inputs and args for all. -O3-equivalent flags, no native-CPU flags:
  Rust `rustc -C opt-level=3 -C codegen-units=1`; Zig `-OReleaseFast -lc` (no bounds checks);
  Kotlin Native `kotlinc-native -opt` (2.4.21); KotlinX compiler `clang -O3 -flto` (Apple clang 21).
- KotlinX compiler uses system malloc/free by default (row NewKotlin). `--alloc pool` (free-list) is the extra row NewKotlinPool.
- Kotlin Native includes its tracing GC and runtime (inherent; explains higher RSS and >100% CPU from the GC thread).
- Zig `closures` is written as plain loops (Zig has no closures). KotlinX compiler inlines map/filter/forEach/sumOf/count.
- All checksums match across all 5 rows for every case.

## Tables
Best value per metric is **bold** (time, RSS, CPU: lower is better; throughput metrics, if any: higher is better).

### sum  (checksums match)

| Participant | Time, ns (lower is better) | Peak RSS, MB (lower is better) | CPU, % (lower is better) |
|---|---|---|---|
| Rust | 3 711 611 | 1.90 | 75.0 |
| Zig | 5 557 680 | 1.86 | 79.4 |
| KotlinNative | 5 808 569 | 6.48 | 71.4 |
| NewKotlin | 3 683 708 | **1.72** | 75.0 |
| NewKotlinPool | **3 683 541** | **1.72** | **71.1** |

### alloc  (checksums match)

| Participant | Time, ns (lower is better) | Peak RSS, MB (lower is better) | CPU, % (lower is better) |
|---|---|---|---|
| Rust | 163 944 639 | 8.32 | 97.6 |
| Zig | 155 178 736 | **8.31** | 97.5 |
| KotlinNative | 215 181 208 | 38.29 | 111.3 |
| NewKotlin | 190 827 444 | 12.18 | 98.0 |
| NewKotlinPool | **53 286 514** | 12.46 | **93.1** |

### closures  (checksums match)

| Participant | Time, ns (lower is better) | Peak RSS, MB (lower is better) | CPU, % (lower is better) |
|---|---|---|---|
| Rust | **23 814 764** | **30.33** | 89.4 |
| Zig | 38 636 611 | 177.17 | **87.5** |
| KotlinNative | 545 943 250 | 228.28 | 112.5 |
| NewKotlin | 90 661 250 | 213.49 | 94.7 |
| NewKotlinPool | 90 004 431 | 213.92 | 94.1 |

### dispatch  (checksums match)

| Participant | Time, ns (lower is better) | Peak RSS, MB (lower is better) | CPU, % (lower is better) |
|---|---|---|---|
| Rust | 84 321 292 | 1.99 | 95.6 |
| Zig | **83 775 819** | 1.99 | 95.6 |
| KotlinNative | 95 589 098 | 7.45 | **95.5** |
| NewKotlin | 95 695 306 | **1.81** | 96.1 |
| NewKotlinPool | 95 714 028 | 1.83 | 96.1 |

### file  (checksums match)

| Participant | Time, ns (lower is better) | Peak RSS, MB (lower is better) | CPU, % (lower is better) |
|---|---|---|---|
| Rust | 80 348 445 | 51.92 | 95.4 |
| Zig | 50 329 583 | 51.91 | 91.8 |
| KotlinNative | 50 113 597 | 56.56 | **91.0** |
| NewKotlin | **50 034 722** | **51.73** | 92.9 |
| NewKotlinPool | 50 409 750 | 51.75 | 92.2 |

### hashmap  (checksums match)

| Participant | Time, ns (lower is better) | Peak RSS, MB (lower is better) | CPU, % (lower is better) |
|---|---|---|---|
| Rust | 76 573 153 | 70.19 | 95.1 |
| Zig | 91 970 347 | **54.12** | 95.9 |
| KotlinNative | 185 031 139 | 170.59 | 112.4 |
| NewKotlin | **71 800 667** | 67.03 | **92.5** |
| NewKotlinPool | 73 015 750 | 67.19 | 94.8 |

### matmul  (checksums match)

| Participant | Time, ns (lower is better) | Peak RSS, MB (lower is better) | CPU, % (lower is better) |
|---|---|---|---|
| Rust | **9 676 278** | 3.44 | **77.8** |
| Zig | 21 239 403 | 3.39 | 85.8 |
| KotlinNative | 41 954 167 | 8.05 | 89.6 |
| NewKotlin | 17 893 000 | **3.28** | 85.7 |
| NewKotlinPool | 17 966 222 | **3.28** | 81.9 |

### nbody  (checksums match)

| Participant | Time, ns (lower is better) | Peak RSS, MB (lower is better) | CPU, % (lower is better) |
|---|---|---|---|
| Rust | 68 739 222 | 1.92 | 94.6 |
| Zig | **25 381 388** | 1.88 | **84.7** |
| KotlinNative | 135 743 166 | 15.66 | 98.6 |
| NewKotlin | 77 771 778 | **1.72** | 95.1 |
| NewKotlinPool | 77 517 652 | 1.77 | 96.7 |

### recursion  (checksums match)

| Participant | Time, ns (lower is better) | Peak RSS, MB (lower is better) | CPU, % (lower is better) |
|---|---|---|---|
| Rust | 6 108 237 | 1.91 | 66.7 |
| Zig | 6 298 791 | 1.84 | 75.4 |
| KotlinNative | **5 705 611** | 6.47 | 66.7 |
| NewKotlin | 6 085 417 | **1.72** | 71.4 |
| NewKotlinPool | 5 999 528 | **1.72** | **63.9** |

### sort  (checksums match)

| Participant | Time, ns (lower is better) | Peak RSS, MB (lower is better) | CPU, % (lower is better) |
|---|---|---|---|
| Rust | 62 052 111 | 9.55 | 94.1 |
| Zig | **59 159 625** | 9.53 | 93.9 |
| KotlinNative | 60 975 320 | 14.11 | 94.2 |
| NewKotlin | 63 003 763 | **9.38** | 94.1 |
| NewKotlinPool | 63 413 778 | **9.38** | **93.2** |

### strings  (checksums match)

| Participant | Time, ns (lower is better) | Peak RSS, MB (lower is better) | CPU, % (lower is better) |
|---|---|---|---|
| Rust | 35 062 403 | 33.22 | 92.0 |
| Zig | 34 859 194 | 33.07 | 89.2 |
| KotlinNative | 118 482 430 | 64.86 | 100.9 |
| NewKotlin | **30 922 139** | **33.03** | 87.7 |
| NewKotlinPool | 31 213 125 | **33.03** | **87.5** |

## No-regression check (KotlinX compiler native, old table vs this round)
| Case | Old (pool, -O3 -flto) | Now pool | Now malloc |
|---|---|---|---|
| sum | 3 695 583 | 3 683 541 | 3 683 708 |
| alloc | 52 725 611 | 53 286 514 | 190 827 444 |
| recursion | 6 173 361 | 5 999 528 | 6 085 417 |
| file | 51 461 833 | 50 409 750 | 50 034 722 |

The backend split / `--target` adds no runtime cost (within noise). alloc on malloc is slower by design (equal conditions); the pool row preserves the old number.

## Optimization history (KotlinX compiler)
1. v1: -O2, malloc per object — alloc 185 243 611 ns.
2. -O3 -flto with runtime.c in the same LTO unit; inlined RC release.
3. Free-list pool allocator — alloc ~52.7M ns (now optional `--alloc pool`).
4. Fast integer append in StringBuilder; open-addressing HashMap in runtime.c.
5. Inline expansion of collection lambdas (map/filter/forEach/sumOf/count).
6. Default switched back to malloc for equal conditions; pool kept as an extra row.

## Memory benchmarks (round 2026-10-28 21:22 EET)

Same conditions as above (round-robin, warm-up, middle 3 of 5). Load average 12.1 at start and 10.6 at end (other jobs running). Cases: alloc, free (three object sizes, replace and drop), refpass (argument passing plus a shared field store), trees (binary trees, depths 4..16), cycles (rings of 100 nodes). Memory handling differs by language: Rust uses Box/Rc and breaks its rings by hand; Zig frees by hand with malloc/free; Kotlin Native uses a tracing GC; KotlinX compiler uses Perceus RC plus the cycle collector. Best value per metric is **bold**.

### alloc  (checksums match)

| Participant | Time, ns (lower is better) | Peak RSS, MB (lower is better) | CPU, % (lower is better) |
|---|---|---|---|
| Rust | 164 974 458 | 8.31 | 96.9 |
| Zig | 155 276 986 | **8.30** | 97.1 |
| KotlinNative | 225 163 931 | 38.31 | 111.6 |
| NewKotlin | 189 381 111 | 12.21 | 97.3 |
| NewKotlinPool | **53 571 986** | 12.47 | **93.6** |

### free  (checksums match)

| Participant | Time, ns (lower is better) | Peak RSS, MB (lower is better) | CPU, % (lower is better) |
|---|---|---|---|
| Rust | 105 899 069 | 16.47 | 97.0 |
| Zig | 90 883 986 | **15.29** | 96.0 |
| KotlinNative | 120 207 708 | 51.88 | 111.5 |
| NewKotlin | 115 079 722 | 17.30 | 97.2 |
| NewKotlinPool | **43 146 597** | 15.83 | **94.0** |

### refpass  (checksums match)

| Participant | Time, ns (lower is better) | Peak RSS, MB (lower is better) | CPU, % (lower is better) |
|---|---|---|---|
| Rust | 51 945 305 | 1.96 | 93.6 |
| Zig | **27 693 667** | 1.95 | **92.6** |
| KotlinNative | 128 664 681 | 6.66 | 97.1 |
| NewKotlin | 142 198 514 | **1.81** | 97.3 |
| NewKotlinPool | 163 078 375 | 1.84 | 97.5 |

### trees  (checksums match)

| Participant | Time, ns (lower is better) | Peak RSS, MB (lower is better) | CPU, % (lower is better) |
|---|---|---|---|
| Rust | 305 050 028 | 5.99 | 98.3 |
| Zig | 278 959 430 | **5.95** | 98.4 |
| KotlinNative | 238 144 042 | 33.14 | 107.3 |
| NewKotlin | 315 203 445 | 9.81 | 98.8 |
| NewKotlinPool | **102 086 542** | 9.78 | **95.9** |

### cycles  (checksums match)

| Participant | Time, ns (lower is better) | Peak RSS, MB (lower is better) | CPU, % (lower is better) |
|---|---|---|---|
| Rust | 82 322 764 | 1.97 | 94.7 |
| Zig | 61 676 861 | **1.91** | 94.2 |
| KotlinNative | **17 477 736** | 15.80 | 114.3 |
| NewKotlin | 103 250 486 | 3.00 | 95.9 |
| NewKotlinPool | 48 067 181 | 2.81 | **92.8** |
