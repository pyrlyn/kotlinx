# lru (median of 5 runs; 2026-10-31 01:25 EET; commit dbb59ca)

Columns: default GC, epoch-base = #11 gc/epoch (2b94196), epoch = main dbb59ca (#41 + #39); KX_GC_THREADS=4. Load avg at start { 38.66 43.40 36.23 }, at end { 48.86 38.31 35.24 } (16 cores).

| max GC pause ms | default | epoch-base | epoch |
|---|---|---|---|
| lru | **4.14** | 4.239 | 4.245 |

| peak RSS MiB | default | epoch-base | epoch |
|---|---|---|---|
| lru | **29.7** | 32.6 | 36.9 |

