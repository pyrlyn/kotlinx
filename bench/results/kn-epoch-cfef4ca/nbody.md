# nbody: KotlinX (epoch GC) vs Kotlin/Native

Config: KotlinX `newkotlinc` @ cfef4ca, `--gc=epoch` (via NEWKOTLIN_GC=epoch at build; incremental major on by default, KX_GC_INC_MAJOR unset), mimalloc. Kotlin/Native 2.4.21 `-opt`. Harness: bench/round.sh, 1 warm-up + 5 interleaved runs, median of in-program `nanos`. Machine: Apple M3 Max 16 cpus; load avg 21-50 during run (concurrent GC experiments), see conditions.txt.

| row | median ms | runs ms |
|---|---|---|
| KotlinX epoch | **72.31** | 71.70, 73.37, 72.64, 70.93, 72.31 |
| Kotlin/Native | 135.47 | 133.91, 137.07, 139.17, 135.47, 134.39 |

Faster: KotlinX; K/N / KotlinX = 1.87x. Checksums match: True.
