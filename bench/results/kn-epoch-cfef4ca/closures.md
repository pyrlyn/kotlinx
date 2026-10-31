# closures: KotlinX (epoch GC) vs Kotlin/Native

Config: KotlinX `newkotlinc` @ cfef4ca, `--gc=epoch` (via NEWKOTLIN_GC=epoch at build; incremental major on by default, KX_GC_INC_MAJOR unset), mimalloc. Kotlin/Native 2.4.21 `-opt`. Harness: bench/round.sh, 1 warm-up + 5 interleaved runs, median of in-program `nanos`. Machine: Apple M3 Max 16 cpus; load avg 21-50 during run (concurrent GC experiments), see conditions.txt.

| row | median ms | runs ms |
|---|---|---|
| KotlinX epoch | **103.58** | 94.48, 103.58, 106.18, 92.90, 103.60 |
| Kotlin/Native | 652.26 | 709.68, 652.26, 647.82, 648.80, 685.55 |

Faster: KotlinX; K/N / KotlinX = 6.30x. Checksums match: True.
