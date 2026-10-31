# cycles: KotlinX (epoch GC) vs Kotlin/Native

Config: KotlinX `newkotlinc` @ cfef4ca, `--gc=epoch` (via NEWKOTLIN_GC=epoch at build; incremental major on by default, KX_GC_INC_MAJOR unset), mimalloc. Kotlin/Native 2.4.21 `-opt`. Harness: bench/round.sh, 1 warm-up + 5 interleaved runs, median of in-program `nanos`. Machine: Apple M3 Max 16 cpus; load avg 21-50 during run (concurrent GC experiments), see conditions.txt.

| row | median ms | runs ms |
|---|---|---|
| KotlinX epoch | 179.49 | 171.63, 179.49, 190.34, 184.11, 175.65 |
| Kotlin/Native | **21.96** | 26.53, 21.96, 20.37, 22.37, 21.23 |

Faster: Kotlin/Native; K/N / KotlinX = 0.12x. Checksums match: True.
