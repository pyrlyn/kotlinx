# live_heap_churn (median of 5 runs; 2026-10-30 21:55 EET; commit d9c3976)

Columns: default GC, epoch-base = #11 gc/epoch, epoch = this branch at d9c3976 (bounded queue, mmap slabs). Median of 5; machine load avg 40-60 on 16 cores.

| max GC pause ms | default | epoch-base | epoch |
|---|---|---|---|
| live_heap_churn | 6.022 | 12.791 | **2.011** |

| peak RSS MiB | default | epoch-base | epoch |
|---|---|---|---|
| live_heap_churn | **25.7** | 54.2 | 35.1 |

