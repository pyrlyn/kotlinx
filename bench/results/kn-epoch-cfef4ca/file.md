# file: KotlinX (epoch GC) vs Kotlin/Native

Config: KotlinX `newkotlinc` @ cfef4ca, `--gc=epoch` (via NEWKOTLIN_GC=epoch at build; incremental major on by default, KX_GC_INC_MAJOR unset), mimalloc. Kotlin/Native 2.4.21 `-opt`. Harness: bench/round.sh, 1 warm-up + 5 interleaved runs, median of in-program `nanos`. Machine: Apple M3 Max 16 cpus; load avg 21-50 during run (concurrent GC experiments), see conditions.txt.

| row | median ms | runs ms |
|---|---|---|
| KotlinX epoch | 54.48 | 56.13, 54.02, 54.48, 52.68, 56.99 |
| Kotlin/Native | **51.09** | 51.04, 49.89, 51.11, 51.09, 56.79 |

Faster: Kotlin/Native; K/N / KotlinX = 0.94x. Checksums match: True.
