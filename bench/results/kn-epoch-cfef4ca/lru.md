# lru: KotlinX (epoch GC) vs Kotlin/Native

Config: KotlinX `newkotlinc` @ cfef4ca, `--gc=epoch` (via NEWKOTLIN_GC=epoch at build; incremental major on by default, KX_GC_INC_MAJOR unset), mimalloc. Kotlin/Native 2.4.21 `-opt`. Harness: bench/round.sh, 1 warm-up + 5 interleaved runs, median of in-program `nanos`. Machine: Apple M3 Max 16 cpus; load avg 21-50 during run (concurrent GC experiments), see conditions.txt.

| row | median ms | runs ms |
|---|---|---|
| KotlinX epoch | 328.33 | 422.58, 328.33, 280.29, 428.67, 304.17 |
| Kotlin/Native | **123.45** | 143.39, 116.81, 108.82, 150.92, 123.45 |

Faster: Kotlin/Native; K/N / KotlinX = 0.38x. Checksums match: True.
