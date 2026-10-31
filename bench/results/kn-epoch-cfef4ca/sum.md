# sum: KotlinX (epoch GC) vs Kotlin/Native

Config: KotlinX `newkotlinc` @ cfef4ca, `--gc=epoch` (via NEWKOTLIN_GC=epoch at build; incremental major on by default, KX_GC_INC_MAJOR unset), mimalloc. Kotlin/Native 2.4.21 `-opt`. Harness: bench/round.sh, 1 warm-up + 5 interleaved runs, median of in-program `nanos`. Machine: Apple M3 Max 16 cpus; load avg 21-50 during run (concurrent GC experiments), see conditions.txt.

| row | median ms | runs ms |
|---|---|---|
| KotlinX epoch | **4.22** | 4.22, 4.06, 4.83, 4.23, 4.22 |
| Kotlin/Native | 6.85 | 6.97, 6.06, 7.11, 6.25, 6.85 |

Faster: KotlinX; K/N / KotlinX = 1.62x. Checksums match: True.
