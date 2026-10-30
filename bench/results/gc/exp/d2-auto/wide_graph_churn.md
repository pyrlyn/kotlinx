# wide_graph_churn (median of 3 runs; 2026-10-30 21:22 EET; load { 35.51 48.64 47.83 }; commit eb64df5)

Columns: default GC, epoch on 1 thread, epoch on 4 threads with automatic parallel/serial choice (this branch at 21e89f9, measured on eb64df5 + this change; machine load avg 40-60 on 16 cores).

| max GC pause ms | default | epoch-T1 | epoch-T4 |
|---|---|---|---|
| wide_graph_churn | 1991.91 | 415.332 | **145.129** |

| run time ms | default | epoch-T1 | epoch-T4 |
|---|---|---|---|
| wide_graph_churn | 111157 | 4927.06 | **3325.66** |

| peak RSS MiB | default | epoch-T1 | epoch-T4 |
|---|---|---|---|
| wide_graph_churn | **742.8** | 992.9 | 998 |

