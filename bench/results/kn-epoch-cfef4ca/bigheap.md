# bigheap: KotlinX (epoch GC) vs Kotlin/Native

Config: KotlinX `newkotlinc` @ cfef4ca, `--gc=epoch` (via NEWKOTLIN_GC=epoch at build; incremental major on by default, KX_GC_INC_MAJOR unset), mimalloc. Kotlin/Native 2.4.21 `-opt`. Harness: bench/round.sh, 1 warm-up + 5 interleaved runs, median of in-program `nanos`. Machine: Apple M3 Max 16 cpus; load avg 21-50 during run (concurrent GC experiments), see conditions.txt.

| row | median ms | runs ms |
|---|---|---|
| KotlinX epoch | **97.58** | 97.58, 117.25, 96.40, 96.37, 105.32 |
| Kotlin/Native | 121.42 | 128.23, 115.88, 121.33, 121.42, 124.41 |

Faster: KotlinX; K/N / KotlinX = 1.24x. Checksums match: True.
