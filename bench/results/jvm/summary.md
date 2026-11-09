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
| sum | 9.5 | 3.8 | 5.8 | 7.4 |
| alloc | 77.7 | 78.8 | 204.1 | 85.8 |
| bigheap | 246.0 | 82.1 | 94.5 | 32.1 |
| closures | 118.5 | 104.5 | 552.8 | 246.6 |
| cycles | 159.5 | 91.7 | 17.0 | 28.3 |
| dispatch | 47.5 | 105.8 | 94.8 | 135.3 |
| file | 100.7 | 51.3 | 52.6 | n/a |
| free | 81.3 | 55.2 | 112.9 | 65.8 |
| hashmap | 137.3 | 114.4 | 183.0 | 71.9 |
| lru | 238.3 | 263.3 | 124.8 | 82.9 |
| matmul | 53.7 | 17.4 | 42.3 | 23.4 |
| nbody | 110.2 | 71.5 | 133.2 | 52.3 |
| recursion | 4.4 | 5.7 | 5.8 | 6.8 |
| refpass | 127.1 | 141.8 | 127.3 | 60.4 |
| sort | 81.6 | 61.4 | 61.9 | 77.9 |
| strings | 48.3 | 36.3 | 117.6 | 50.0 |
| trees | 317.8 | 122.1 | 219.7 | 64.9 |

## Wall time of the whole process (s, includes JVM start-up)

| case | kxjvm | kxnative | kn | kjvm |
|---|---:|---:|---:|---:|
| sum | 0.05 | 0.00 | 0.01 | 0.04 |
| alloc | 0.13 | 0.08 | 0.21 | 0.12 |
| bigheap | 0.32 | 0.24 | 0.10 | 0.06 |
| closures | 0.18 | 0.11 | 0.58 | 0.29 |
| cycles | 0.22 | 0.10 | 0.02 | 0.06 |
| dispatch | 0.09 | 0.11 | 0.10 | 0.17 |
| file | 0.15 | 0.05 | 0.05 | n/a |
| free | 0.14 | 0.06 | 0.12 | 0.10 |
| hashmap | 0.19 | 0.12 | 0.20 | 0.10 |
| lru | 0.31 | 0.29 | 0.13 | 0.11 |
| matmul | 0.12 | 0.02 | 0.05 | 0.06 |
| nbody | 0.15 | 0.07 | 0.14 | 0.09 |
| recursion | 0.05 | 0.01 | 0.01 | 0.04 |
| refpass | 0.17 | 0.15 | 0.13 | 0.09 |
| sort | 0.13 | 0.06 | 0.06 | 0.11 |
| strings | 0.10 | 0.04 | 0.12 | 0.08 |
| trees | 0.37 | 0.12 | 0.22 | 0.10 |

## Peak RSS (MiB)

| case | kxjvm | kxnative | kn | kjvm |
|---|---:|---:|---:|---:|
| sum | 45.2 | 2.4 | 6.5 | 42.4 |
| alloc | 59.0 | 11.7 | 38.4 | 277.9 |
| bigheap | 118.8 | 342.5 | 75.9 | 136.7 |
| closures | 221.4 | 45.0 | 228.3 | 565.0 |
| cycles | 62.3 | 28.0 | 15.8 | 107.0 |
| dispatch | 45.9 | 2.5 | 7.5 | 44.8 |
| file | 97.8 | 52.4 | 56.7 | n/a |
| free | 67.6 | 13.7 | 52.0 | 256.8 |
| hashmap | 101.0 | 54.6 | 172.9 | 185.1 |
| lru | 84.8 | 35.1 | 19.3 | 101.5 |
| matmul | 49.8 | 3.9 | 8.1 | 46.3 |
| nbody | 50.7 | 2.4 | 15.7 | 45.9 |
| recursion | 45.1 | 2.4 | 6.5 | 48.8 |
| refpass | 46.5 | 2.5 | 6.7 | 44.2 |
| sort | 53.4 | 10.0 | 14.1 | 52.8 |
| strings | 76.8 | 35.1 | 64.9 | 98.0 |
| trees | 57.3 | 10.3 | 34.5 | 227.6 |

## File size (KiB)

kxjvm is self-contained (program + runtime classes in one jar). kjvm is the program jar alone; it also needs kotlin-stdlib.jar (1810 KiB). Both JVM rows need a JRE.

| case | kxjvm | kxnative | kn | kjvm |
|---|---:|---:|---:|---:|
| sum | 21 | 449 | 482 | 2 |
| alloc | 22 | 449 | 502 | 3 |
| bigheap | 25 | 450 | 503 | 6 |
| closures | 22 | 449 | 502 | 3 |
| cycles | 22 | 449 | 498 | 4 |
| dispatch | 23 | 450 | 503 | 5 |
| file | 22 | 449 | 503 | n/a |
| free | 23 | 450 | 502 | 5 |
| hashmap | 24 | 449 | 498 | 2 |
| lru | 25 | 450 | 502 | 5 |
| matmul | 21 | 449 | 498 | 2 |
| nbody | 23 | 449 | 502 | 4 |
| recursion | 21 | 449 | 482 | 2 |
| refpass | 23 | 449 | 502 | 4 |
| sort | 21 | 449 | 498 | 2 |
| strings | 22 | 449 | 498 | 2 |
| trees | 22 | 449 | 498 | 3 |
