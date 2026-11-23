# hashmap: ZKotlin (epoch GC) vs Kotlin/Native

Config: ZKotlin `newkotlinc` @ cfef4ca, `--gc=epoch` (via NEWKOTLIN_GC=epoch at build; incremental major on by default, KX_GC_INC_MAJOR unset), mimalloc. Kotlin/Native 2.4.21 `-opt`. Harness: bench/round.sh, 1 warm-up + 5 interleaved runs, median of in-program `nanos`. Machine: Apple M3 Max 16 cpus; load avg 21-50 during run (concurrent GC experiments), see conditions.txt.

| row | median ms | runs ms |
|---|---|---|
| ZKotlin epoch | **72.34** | 77.36, 72.34, 70.47, 69.85, 72.45 |
| Kotlin/Native | 196.12 | 196.60, 196.70, 196.12, 184.79, 193.30 |

Faster: ZKotlin; K/N / ZKotlin = 2.71x. Checksums match: True.
