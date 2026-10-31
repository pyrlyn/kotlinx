# free: KotlinX (epoch GC) vs Kotlin/Native

Config: KotlinX `newkotlinc` @ cfef4ca, `--gc=epoch` (via NEWKOTLIN_GC=epoch at build; incremental major on by default, KX_GC_INC_MAJOR unset), mimalloc. Kotlin/Native 2.4.21 `-opt`. Harness: bench/round.sh, 1 warm-up + 5 interleaved runs, median of in-program `nanos`. Machine: Apple M3 Max 16 cpus; load avg 21-50 during run (concurrent GC experiments), see conditions.txt.

| row | median ms | runs ms |
|---|---|---|
| KotlinX epoch | **58.61** | 61.54, 55.95, 57.71, 58.61, 60.60 |
| Kotlin/Native | 131.52 | 143.00, 132.18, 131.52, 131.37, 123.12 |

Faster: KotlinX; K/N / KotlinX = 2.24x. Checksums match: True.
