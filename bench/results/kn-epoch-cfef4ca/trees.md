# trees: KotlinX (epoch GC) vs Kotlin/Native

Config: KotlinX `newkotlinc` @ cfef4ca, `--gc=epoch` (via NEWKOTLIN_GC=epoch at build; incremental major on by default, KX_GC_INC_MAJOR unset), mimalloc. Kotlin/Native 2.4.21 `-opt`. Harness: bench/round.sh, 1 warm-up + 5 interleaved runs, median of in-program `nanos`. Machine: Apple M3 Max 16 cpus; load avg 21-50 during run (concurrent GC experiments), see conditions.txt.

| row | median ms | runs ms |
|---|---|---|
| KotlinX epoch | **123.76** | 122.01, 123.76, 123.01, 127.13, 125.37 |
| Kotlin/Native | 238.69 | 229.07, 238.77, 226.38, 239.27, 238.69 |

Faster: KotlinX; K/N / KotlinX = 1.93x. Checksums match: True.
