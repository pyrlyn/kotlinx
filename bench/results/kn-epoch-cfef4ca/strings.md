# strings: KotlinX (epoch GC) vs Kotlin/Native

Config: KotlinX `newkotlinc` @ cfef4ca, `--gc=epoch` (via NEWKOTLIN_GC=epoch at build; incremental major on by default, KX_GC_INC_MAJOR unset), mimalloc. Kotlin/Native 2.4.21 `-opt`. Harness: bench/round.sh, 1 warm-up + 5 interleaved runs, median of in-program `nanos`. Machine: Apple M3 Max 16 cpus; load avg 21-50 during run (concurrent GC experiments), see conditions.txt.

| row | median ms | runs ms |
|---|---|---|
| KotlinX epoch | **32.82** | 31.78, 32.01, 33.95, 32.82, 33.09 |
| Kotlin/Native | 122.99 | 117.76, 127.18, 119.76, 122.99, 127.59 |

Faster: KotlinX; K/N / KotlinX = 3.75x. Checksums match: True.
