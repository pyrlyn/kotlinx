# lru (median of 3 runs; 2026-10-30 21:22 EET; load { 32.35 47.53 47.44 }; commit eb64df5)

Columns: default GC, epoch on 1 thread, epoch on 4 threads with automatic parallel/serial choice (this branch at 21e89f9, measured on eb64df5 + this change; machine load avg 40-60 on 16 cores).

| max GC pause ms | default | epoch-T1 | epoch-T4 |
|---|---|---|---|
| lru | **3.49** | 5.823 | 5.097 |

| run time ms | default | epoch-T1 | epoch-T4 |
|---|---|---|---|
| lru | **98.955** | 403.019 | 413.34 |

| peak RSS MiB | default | epoch-T1 | epoch-T4 |
|---|---|---|---|
| lru | **29.7** | 32.1 | 32.4 |

