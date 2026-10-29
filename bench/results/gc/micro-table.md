| case, ms | default | incremental | rc |
|---|---|---|---|
| alloc_throughput | 80.316 | 80.577 | **79.634** |
| cyclic_garbage | 56.478 | 57.196 | **15.297** |
| live_heap_churn | 484.865 | 428.134 | **29.705** |
| short_lived | 77.332 | **77.004** | 77.813 |

| case, peak RSS MiB | default | incremental | rc |
|---|---|---|---|
| alloc_throughput | **2.2** | **2.2** | **2.2** |
| cyclic_garbage | 4.4 | **3.7** | 125 |
| live_heap_churn | **25.7** | 32.3 | 166.9 |
| short_lived | **2.2** | **2.2** | **2.2** |

| case, total GC pause ms | default | incremental | rc |
|---|---|---|---|
| alloc_throughput | **0** | **0** | **0** |
| cyclic_garbage | 47.319 | 46.283 | **0** |
| live_heap_churn | 447.04 | 393.093 | **0** |
| short_lived | **0** | **0** | **0** |

| case, max GC pause ms | default | incremental | rc |
|---|---|---|---|
| alloc_throughput | **0** | **0** | **0** |
| cyclic_garbage | 0.542 | 0.469 | **0** |
| live_heap_churn | 5.527 | 8.122 | **0** |
| short_lived | **0** | **0** | **0** |

| case, collections | default | incremental | rc |
|---|---|---|---|
| alloc_throughput | **0** | **0** | **0** |
| cyclic_garbage | 250 | 250 | **0** |
| live_heap_churn | 130 | 97 | **0** |
| short_lived | **0** | **0** | **0** |

