# refpass: KotlinX (epoch GC) vs Kotlin/Native

Config: KotlinX `newkotlinc` @ cfef4ca, `--gc=epoch` (via NEWKOTLIN_GC=epoch at build; incremental major on by default, KX_GC_INC_MAJOR unset), mimalloc. Kotlin/Native 2.4.21 `-opt`. Harness: bench/round.sh, 1 warm-up + 5 interleaved runs, median of in-program `nanos`. Machine: Apple M3 Max 16 cpus; load avg 21-50 during run (concurrent GC experiments), see conditions.txt.

| row | median ms | runs ms |
|---|---|---|
| KotlinX epoch | **130.19** | 130.81, 130.19, 128.27, 126.31, 134.92 |
| Kotlin/Native | 132.09 | 132.09, 132.63, 131.82, 133.69, 130.61 |

Faster: KotlinX; K/N / KotlinX = 1.01x. Checksums match: True.
