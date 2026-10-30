# sort: KotlinX (epoch GC) vs Kotlin/Native

Config: KotlinX `newkotlinc` @ cfef4ca, `--gc=epoch` (via NEWKOTLIN_GC=epoch at build; incremental major on by default, KX_GC_INC_MAJOR unset), mimalloc. Kotlin/Native 2.4.21 `-opt`. Harness: bench/round.sh, 1 warm-up + 5 interleaved runs, median of in-program `nanos`. Machine: Apple M3 Max 16 cpus; load avg 21-50 during run (concurrent GC experiments), see conditions.txt.

| row | median ms | runs ms |
|---|---|---|
| KotlinX epoch | 62.43 | 64.98, 64.54, 62.40, 62.43, 62.13 |
| Kotlin/Native | **61.74** | 63.05, 61.54, 62.61, 60.48, 61.74 |

Faster: Kotlin/Native; K/N / KotlinX = 0.99x. Checksums match: True.
