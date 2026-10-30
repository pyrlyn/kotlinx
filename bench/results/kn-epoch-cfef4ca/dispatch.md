# dispatch: KotlinX (epoch GC) vs Kotlin/Native

Config: KotlinX `newkotlinc` @ cfef4ca, `--gc=epoch` (via NEWKOTLIN_GC=epoch at build; incremental major on by default, KX_GC_INC_MAJOR unset), mimalloc. Kotlin/Native 2.4.21 `-opt`. Harness: bench/round.sh, 1 warm-up + 5 interleaved runs, median of in-program `nanos`. Machine: Apple M3 Max 16 cpus; load avg 21-50 during run (concurrent GC experiments), see conditions.txt.

| row | median ms | runs ms |
|---|---|---|
| KotlinX epoch | **109.03** | 111.35, 106.75, 116.12, 101.27, 109.03 |
| Kotlin/Native | 111.24 | 115.00, 106.68, 113.62, 111.24, 109.28 |

Faster: KotlinX; K/N / KotlinX = 1.02x. Checksums match: True.
