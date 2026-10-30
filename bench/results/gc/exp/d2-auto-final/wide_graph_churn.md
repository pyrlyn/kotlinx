# wide_graph_churn (median of 5 runs; 2026-10-30 23:07 EET; commit 06fb851)

Columns: default GC, epoch-base = #11 gc/epoch, epoch = #39 at 06fb851 (automatic parallel detection); all runs KX_GC_THREADS=4. Load avg at start { 23.51 34.81 45.54 }, at end { 37.85 37.86 39.96 } (16 cores).

| max GC pause ms | default | epoch-base | epoch |
|---|---|---|---|
| wide_graph_churn | 1884.46 | 370.879 | **175.83** |

| peak RSS MiB | default | epoch-base | epoch |
|---|---|---|---|
| wide_graph_churn | **744** | 1015.8 | 998 |

