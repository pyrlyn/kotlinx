# huge_live_churn (median of 5 runs; 2026-10-31 01:27 EET; commit dbb59ca)

Columns: default GC, epoch-base = #11 gc/epoch (2b94196), epoch = main dbb59ca (#41 + #39); KX_GC_THREADS=4. Load avg at start { 38.66 43.40 36.23 }, at end { 48.86 38.31 35.24 } (16 cores).

| max GC pause ms | default | epoch-base | epoch |
|---|---|---|---|
| huge_live_churn | 588.954 | 537.438 | **121.502** |

| peak RSS MiB | default | epoch-base | epoch |
|---|---|---|---|
| huge_live_churn | 722.2 | 1046.9 | **699.8** |

