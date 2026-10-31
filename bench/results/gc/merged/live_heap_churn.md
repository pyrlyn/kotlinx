# live_heap_churn (median of 5 runs; 2026-10-31 01:25 EET; commit dbb59ca)

Columns: default GC, epoch-base = #11 gc/epoch (2b94196), epoch = main dbb59ca (#41 + #39); KX_GC_THREADS=4. Load avg at start { 38.66 43.40 36.23 }, at end { 48.86 38.31 35.24 } (16 cores).

| max GC pause ms | default | epoch-base | epoch |
|---|---|---|---|
| live_heap_churn | 6.246 | 14.596 | **2.765** |

| peak RSS MiB | default | epoch-base | epoch |
|---|---|---|---|
| live_heap_churn | **25.7** | 54.1 | 37.5 |

