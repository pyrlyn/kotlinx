# matmul: KotlinX (epoch GC) vs Kotlin/Native

Config: KotlinX `newkotlinc` @ cfef4ca, `--gc=epoch` (via NEWKOTLIN_GC=epoch at build; incremental major on by default, KX_GC_INC_MAJOR unset), mimalloc. Kotlin/Native 2.4.21 `-opt`. Harness: bench/round.sh, 1 warm-up + 5 interleaved runs, median of in-program `nanos`. Machine: Apple M3 Max 16 cpus; load avg 21-50 during run (concurrent GC experiments), see conditions.txt.

| row | median ms | runs ms |
|---|---|---|
| KotlinX epoch | **10.10** | 10.17, 10.35, 9.82, 10.10, 9.71 |
| Kotlin/Native | 43.02 | 43.02, 42.00, 43.95, 42.18, 44.13 |

Faster: KotlinX; K/N / KotlinX = 4.26x. Checksums match: True.
