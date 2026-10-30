# live_heap_churn (median of 3 runs; 2026-10-30 21:15 EET; load { 40.81 42.04 43.24 }; commit eb64df5)

Columns: default GC, epoch on 1 thread, epoch on 4 threads with automatic parallel/serial choice (this branch at 21e89f9, measured on eb64df5 + this change; machine load avg 40-60 on 16 cores).

| max GC pause ms | default | epoch-T1 | epoch-T4 |
|---|---|---|---|
| live_heap_churn | 11.821 | 10.159 | **9.514** |

| run time ms | default | epoch-T1 | epoch-T4 |
|---|---|---|---|
| live_heap_churn | 651.59 | **477.2** | 477.353 |

| peak RSS MiB | default | epoch-T1 | epoch-T4 |
|---|---|---|---|
| live_heap_churn | **25.7** | 49.8 | 50.6 |

