# live_heap_churn (median of 3 runs; 2026-10-30 21:04 EET; commit 2f01431)

Columns: default GC, epoch-base = #11 gc/epoch, epoch = this branch at 7685dc8 (measured on the working tree of 2f01431 + this change; machine load avg 40-60 on 16 cores, other jobs running).

| max GC pause ms | default | epoch-base | epoch |
|---|---|---|---|
| live_heap_churn | 5.255 | 12.249 | **2.084** |

| peak RSS MiB | default | epoch-base | epoch |
|---|---|---|---|
| live_heap_churn | **25.6** | 54.7 | 39.3 |

