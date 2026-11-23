# ZKotlin (epoch GC) vs Kotlin/Native @ cfef4ca

Config: ZKotlin `newkotlinc` @ cfef4ca, `--gc=epoch` (via NEWKOTLIN_GC=epoch at build; incremental major on by default, KX_GC_INC_MAJOR unset), mimalloc. Kotlin/Native 2.4.21 `-opt`. Harness: bench/round.sh, 1 warm-up + 5 interleaved runs, median of in-program `nanos`. Machine: Apple M3 Max 16 cpus; load avg 21-50 during run (concurrent GC experiments), see conditions.txt.

| case | ZKotlin ms | K/N ms | K/N÷KX | faster |
|---|---|---|---|---|
| sum | **4.22** | 6.85 | 1.62 | ZKotlin |
| alloc | **81.71** | 237.88 | 2.91 | ZKotlin |
| bigheap | **97.58** | 121.42 | 1.24 | ZKotlin |
| closures | **103.58** | 652.26 | 6.30 | ZKotlin |
| cycles | 179.49 | **21.96** | 0.12 | Kotlin/Native |
| dispatch | **109.03** | 111.24 | 1.02 | ZKotlin |
| file | 54.48 | **51.09** | 0.94 | Kotlin/Native |
| free | **58.61** | 131.52 | 2.24 | ZKotlin |
| hashmap | **72.34** | 196.12 | 2.71 | ZKotlin |
| lru | 328.33 | **123.45** | 0.38 | Kotlin/Native |
| matmul | **10.10** | 43.02 | 4.26 | ZKotlin |
| nbody | **72.31** | 135.47 | 1.87 | ZKotlin |
| recursion | 6.18 | **5.70** | 0.92 | Kotlin/Native |
| refpass | **130.19** | 132.09 | 1.01 | ZKotlin |
| sort | 62.43 | **61.74** | 0.99 | Kotlin/Native |
| strings | **32.82** | 122.99 | 3.75 | ZKotlin |
| trees | **123.76** | 238.69 | 1.93 | ZKotlin |

Geomean K/N÷ZKotlin: 1.446 (>1 = ZKotlin faster). ZKotlin faster in 12/17.
