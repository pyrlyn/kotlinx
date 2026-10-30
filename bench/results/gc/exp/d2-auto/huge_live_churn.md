# huge_live_churn (median of 3 runs; 2026-10-30 21:16 EET; load { 56.88 47.18 45.11 }; commit eb64df5)

Columns: default GC, epoch on 1 thread, epoch on 4 threads with automatic parallel/serial choice (this branch at 21e89f9, measured on eb64df5 + this change; machine load avg 40-60 on 16 cores).

| max GC pause ms | default | epoch-T1 | epoch-T4 |
|---|---|---|---|
| huge_live_churn | 665.672 | 766.491 | **522.608** |

| run time ms | default | epoch-T1 | epoch-T4 |
|---|---|---|---|
| huge_live_churn | 20746.4 | 1713.68 | **1532.84** |

| peak RSS MiB | default | epoch-T1 | epoch-T4 |
|---|---|---|---|
| huge_live_churn | **787.9** | 1135.8 | 1035.2 |

