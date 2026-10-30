# huge_live_churn (median of 5 runs; 2026-10-30 22:57 EET; commit 06fb851)

Columns: default GC, epoch-base = #11 gc/epoch, epoch = #39 at 06fb851 (automatic parallel detection); all runs KX_GC_THREADS=4. Load avg at start { 23.51 34.81 45.54 }, at end { 37.85 37.86 39.96 } (16 cores).

| max GC pause ms | default | epoch-base | epoch |
|---|---|---|---|
| huge_live_churn | 581.242 | **577.512** | 625.38 |

| peak RSS MiB | default | epoch-base | epoch |
|---|---|---|---|
| huge_live_churn | **787.9** | 1045.9 | 1057.1 |

