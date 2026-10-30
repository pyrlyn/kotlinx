# bigheap (median of 3 runs; 2026-10-30 21:15 EET; load { 44.27 42.74 43.48 }; commit eb64df5)

Columns: default GC, epoch on 1 thread, epoch on 4 threads with automatic parallel/serial choice (this branch at 21e89f9, measured on eb64df5 + this change; machine load avg 40-60 on 16 cores).

| max GC pause ms | default | epoch-T1 | epoch-T4 |
|---|---|---|---|
| bigheap | 18.196 | 7.51 | **7.462** |

| run time ms | default | epoch-T1 | epoch-T4 |
|---|---|---|---|
| bigheap | 126.973 | **95.237** | 97.385 |

| peak RSS MiB | default | epoch-T1 | epoch-T4 |
|---|---|---|---|
| bigheap | **60.7** | 252.6 | 247.8 |

