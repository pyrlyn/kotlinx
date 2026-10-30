# cycles (median of 3 runs; 2026-10-30 21:22 EET; load { 33.95 48.10 47.64 }; commit eb64df5)

Columns: default GC, epoch on 1 thread, epoch on 4 threads with automatic parallel/serial choice (this branch at 21e89f9, measured on eb64df5 + this change; machine load avg 40-60 on 16 cores).

| max GC pause ms | default | epoch-T1 | epoch-T4 |
|---|---|---|---|
| cycles | **0.558** | 0.668 | 0.721 |

| run time ms | default | epoch-T1 | epoch-T4 |
|---|---|---|---|
| cycles | **48.318** | 117.529 | 117.486 |

| peak RSS MiB | default | epoch-T1 | epoch-T4 |
|---|---|---|---|
| cycles | **3.3** | 20.1 | 20.5 |

