# cyclic_garbage (median of 3 runs; 2026-10-30 21:15 EET; load { 43.15 42.52 43.41 }; commit eb64df5)

Columns: default GC, epoch on 1 thread, epoch on 4 threads with automatic parallel/serial choice (this branch at 21e89f9, measured on eb64df5 + this change; machine load avg 40-60 on 16 cores).

| max GC pause ms | default | epoch-T1 | epoch-T4 |
|---|---|---|---|
| cyclic_garbage | **0.489** | 1.072 | 1.147 |

| run time ms | default | epoch-T1 | epoch-T4 |
|---|---|---|---|
| cyclic_garbage | **55.864** | 91.904 | 94.711 |

| peak RSS MiB | default | epoch-T1 | epoch-T4 |
|---|---|---|---|
| cyclic_garbage | **4.4** | 91.5 | 91.4 |

