# alloc_throughput (median of 3 runs; 2026-10-30 21:15 EET; load { 43.15 42.52 43.41 }; commit eb64df5)

Columns: default GC, epoch on 1 thread, epoch on 4 threads with automatic parallel/serial choice (this branch at 21e89f9, measured on eb64df5 + this change; machine load avg 40-60 on 16 cores).

| max GC pause ms | default | epoch-T1 | epoch-T4 |
|---|---|---|---|
| alloc_throughput | **0** | **0** | **0** |

| run time ms | default | epoch-T1 | epoch-T4 |
|---|---|---|---|
| alloc_throughput | 82.578 | **80.393** | 80.902 |

| peak RSS MiB | default | epoch-T1 | epoch-T4 |
|---|---|---|---|
| alloc_throughput | **2.2** | **2.2** | **2.2** |

