# recursion: KotlinX (epoch GC) vs Kotlin/Native

Config: KotlinX `newkotlinc` @ cfef4ca, `--gc=epoch` (via NEWKOTLIN_GC=epoch at build; incremental major on by default, KX_GC_INC_MAJOR unset), mimalloc. Kotlin/Native 2.4.21 `-opt`. Harness: bench/round.sh, 1 warm-up + 5 interleaved runs, median of in-program `nanos`. Machine: Apple M3 Max 16 cpus; load avg 21-50 during run (concurrent GC experiments), see conditions.txt.

| row | median ms | runs ms |
|---|---|---|
| KotlinX epoch | 6.18 | 6.24, 6.11, 6.32, 6.18, 5.99 |
| Kotlin/Native | **5.70** | 5.70, 5.48, 5.26, 6.10, 5.89 |

Faster: Kotlin/Native; K/N / KotlinX = 0.92x. Checksums match: True.
