# mini-bench results

Time = kernel ns (mean of middle 3 of 5). Peak RSS from `/usr/bin/time -l`. CPU = (user+sys)/wall over N back-to-back launches x5 (includes process startup): N=200, alloc N=20.
Machine: Apple Silicon Mac (arm64), 2026-10-28.

### sum (unchanged, measured earlier)

| Participant | Time, ns | Peak RSS, MB | CPU, % |
|---|---|---|---|
| Rust | 3 733 014 | 1.91 | 75.1 |
| Zig | 5 723 292 | 1.86 | 70.4 |
| Kotlin Native | 5 880 361 | 6.49 | 74.1 |
| Kotlin -> LLVM (ours) | pending | pending | pending |

### alloc  (checksums match)

| Participant | Time, ns | Peak RSS, MB | CPU, % |
|---|---|---|---|
| Rust | 165 853 291 | 8.30 | 98.4 |
| Zig | 154 317 930 | 7.78 | 98.1 |
| KotlinNative | 216 380 361 | 39.54 | 112.2 |
| Kotlin -> LLVM (ours) | pending | pending | pending |

### recursion  (checksums match)

| Participant | Time, ns | Peak RSS, MB | CPU, % |
|---|---|---|---|
| Rust | 6 069 264 | 1.91 | 83.2 |
| Zig | 6 251 291 | 1.86 | 83.7 |
| KotlinNative | 5 778 722 | 6.47 | 80.3 |
| Kotlin -> LLVM (ours) | pending | pending | pending |

### file  (checksums match)

| Participant | Time, ns | Peak RSS, MB | CPU, % |
|---|---|---|---|
| Rust | 81 889 847 | 51.92 | 96.9 |
| Zig | 50 519 138 | 51.92 | 95.1 |
| KotlinNative | 50 840 680 | 56.59 | 94.0 |
| Kotlin -> LLVM (ours) | pending | pending | pending |
