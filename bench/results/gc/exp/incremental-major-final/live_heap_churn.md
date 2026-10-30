# live_heap_churn (median of 5 runs; 2026-10-30 22:54 EET; commit 9065432)

Columns: default GC, epoch-base = #11 gc/epoch, epoch = #41 at 9065432. Load avg (1/5/15 min) at start { 23.09 36.17 46.45 }, at end { 23.51 34.81 45.54 } (16 cores).

| max GC pause ms | default | epoch-base | epoch |
|---|---|---|---|
| live_heap_churn | 6.102 | 14.243 | **2.097** |

| peak RSS MiB | default | epoch-base | epoch |
|---|---|---|---|
| live_heap_churn | **25.7** | 54.2 | 35 |

