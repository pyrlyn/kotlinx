# alloc: KotlinX (epoch GC) vs Kotlin/Native

Config: KotlinX `newkotlinc` @ cfef4ca, `--gc=epoch` (via NEWKOTLIN_GC=epoch at build; incremental major on by default, KX_GC_INC_MAJOR unset), mimalloc. Kotlin/Native 2.4.21 `-opt`. Harness: bench/round.sh, 1 warm-up + 5 interleaved runs, median of in-program `nanos`. Machine: Apple M3 Max 16 cpus; load avg 21-50 during run (concurrent GC experiments), see conditions.txt.

| row | median ms | runs ms |
|---|---|---|
| KotlinX epoch | **81.71** | 81.42, 85.55, 88.06, 81.71, 75.85 |
| Kotlin/Native | 237.88 | 231.97, 237.88, 251.09, 272.51, 218.55 |

Faster: KotlinX; K/N / KotlinX = 2.91x. Checksums match: True.
