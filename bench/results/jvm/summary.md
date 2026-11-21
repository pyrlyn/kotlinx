# --target jvm benchmark

2026-11-09, Apple M3 Max, openjdk version "21.0.12" 2026-07-21 LTS.
Median of 5 runs; built and run by bench/jvm/run.sh. Rows:

- kxjvm: newkotlinc --target jvm (Kotlin -> LLVM IR -> JVM bytecode), one executable jar
- kxnative: newkotlinc, native defaults
- kn: Kotlin/Native kotlinc-native -opt
- kjvm: Kotlin/JVM kotlinc + kotlin-stdlib.jar, java -Xss1g (cases using posix/cinterop: n/a)

A checksum that differs from the kxnative row is shown as `!checksum`.

## Time of the measured section (ms, the program's own nanos)

| case | kxjvm | kxnative | kn | kjvm |
|---|---:|---:|---:|---:|
| sum | 9.5 | 4.2 | 6.7 | 9.0 |
| alloc | 70.5 | 95.7 | 272.7 | 107.2 |
| bigheap | 169.2 | 106.0 | 110.5 | 42.2 |
| closures | 99.1 | 112.9 | 584.3 | 287.7 |
| cycles | 83.1 | 138.3 | 21.5 | 34.2 |
| dispatch | 50.4 | 119.4 | 114.4 | 162.4 |
| file | 113.0 | 60.9 | 58.3 | n/a |
| free | 72.1 | 63.7 | 130.2 | 87.4 |
| hashmap | 167.8 | 146.5 | 237.7 | 83.2 |
| lru | 181.6 | 495.8 | 181.1 | 100.1 |
| matmul | 34.1 | 18.0 | 46.9 | 25.8 |
| nbody | 69.1 | 75.3 | 133.6 | 53.2 |
| recursion | 4.6 | 5.9 | 5.8 | 7.2 |
| refpass | 108.2 | 149.2 | 147.4 | 65.3 |
| sort | 75.4 | 62.5 | 61.6 | 78.3 |
| strings | 46.1 | 36.1 | 133.4 | 56.4 |
| trees | 86.6 | 134.6 | 253.5 | 70.9 |

## Wall time of the whole process (s, includes JVM start-up)

| case | kxjvm | kxnative | kn | kjvm |
|---|---:|---:|---:|---:|
| sum | 0.11 | 0.01 | 0.01 | 0.06 |
| alloc | 0.13 | 0.11 | 0.29 | 0.15 |
| bigheap | 0.25 | 0.29 | 0.12 | 0.09 |
| closures | 0.18 | 0.12 | 0.62 | 0.34 |
| cycles | 0.14 | 0.16 | 0.03 | 0.07 |
| dispatch | 0.12 | 0.13 | 0.12 | 0.21 |
| file | 0.18 | 0.06 | 0.06 | n/a |
| free | 0.16 | 0.07 | 0.13 | 0.15 |
| hashmap | 0.24 | 0.16 | 0.26 | 0.12 |
| lru | 0.29 | 0.55 | 0.20 | 0.14 |
| matmul | 0.08 | 0.02 | 0.05 | 0.06 |
| nbody | 0.12 | 0.08 | 0.14 | 0.09 |
| recursion | 0.05 | 0.01 | 0.01 | 0.05 |
| refpass | 0.16 | 0.15 | 0.15 | 0.10 |
| sort | 0.13 | 0.06 | 0.06 | 0.11 |
| strings | 0.10 | 0.04 | 0.14 | 0.10 |
| trees | 0.14 | 0.14 | 0.26 | 0.11 |

## Peak RSS (MiB)

| case | kxjvm | kxnative | kn | kjvm |
|---|---:|---:|---:|---:|
| sum | 45.1 | 2.4 | 6.5 | 42.5 |
| alloc | 59.9 | 11.7 | 44.1 | 302.0 |
| bigheap | 110.2 | 342.6 | 75.9 | 137.0 |
| closures | 220.5 | 45.0 | 228.3 | 567.4 |
| cycles | 50.3 | 27.9 | 15.9 | 106.8 |
| dispatch | 46.7 | 2.5 | 7.5 | 44.7 |
| file | 99.3 | 52.4 | 56.7 | n/a |
| free | 65.5 | 13.7 | 52.1 | 257.3 |
| hashmap | 103.9 | 54.5 | 163.0 | 186.5 |
| lru | 74.5 | 34.2 | 19.5 | 101.1 |
| matmul | 48.3 | 3.9 | 8.1 | 46.2 |
| nbody | 50.3 | 2.4 | 15.7 | 46.0 |
| recursion | 44.9 | 2.4 | 6.5 | 49.8 |
| refpass | 46.9 | 2.5 | 6.7 | 44.3 |
| sort | 54.3 | 10.0 | 14.2 | 53.0 |
| strings | 77.7 | 35.1 | 64.9 | 98.0 |
| trees | 57.6 | 10.3 | 34.5 | 227.6 |

## File size (KiB)

kxjvm is self-contained (program + runtime classes in one jar). kjvm is the program jar alone; it also needs kotlin-stdlib.jar (1810 KiB). Both JVM rows need a JRE.

| case | kxjvm | kxnative | kn | kjvm |
|---|---:|---:|---:|---:|
| sum | 21 | 449 | 482 | 2 |
| alloc | 23 | 449 | 502 | 3 |
| bigheap | 25 | 450 | 503 | 6 |
| closures | 22 | 449 | 502 | 3 |
| cycles | 23 | 449 | 498 | 4 |
| dispatch | 24 | 450 | 503 | 5 |
| file | 22 | 449 | 503 | n/a |
| free | 24 | 450 | 502 | 5 |
| hashmap | 25 | 449 | 498 | 2 |
| lru | 26 | 450 | 502 | 5 |
| matmul | 22 | 449 | 498 | 2 |
| nbody | 24 | 449 | 502 | 4 |
| recursion | 21 | 449 | 482 | 2 |
| refpass | 24 | 449 | 502 | 4 |
| sort | 22 | 449 | 498 | 2 |
| strings | 22 | 449 | 498 | 2 |
| trees | 22 | 449 | 498 | 3 |
