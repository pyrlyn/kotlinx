| case, ms | default | incremental | epoch |
|---|---|---|---|
| alloc_throughput | **78.803** | 78.907 | 79.498 |
| cyclic_garbage | **54.095** | 56.086 | 87.539 |
| live_heap_churn | 513.328 | 457.423 | **451.135** |
| short_lived | 82.229 | **77.837** | 80.863 |

| case, peak RSS MiB | default | incremental | epoch |
|---|---|---|---|
| alloc_throughput | **2.2** | **2.2** | **2.2** |
| cyclic_garbage | 4.4 | **3.7** | 94.2 |
| live_heap_churn | **25.6** | 32.3 | 54.7 |
| short_lived | **2.2** | **2.2** | **2.2** |

| case, total GC pause ms | default | incremental | epoch |
|---|---|---|---|
| alloc_throughput | **0** | **0** | **0** |
| cyclic_garbage | **44.792** | 45.984 | 58.332 |
| live_heap_churn | 496.549 | 426.909 | **314.944** |
| short_lived | **0** | **0** | **0** |

| case, max GC pause ms | default | incremental | epoch |
|---|---|---|---|
| alloc_throughput | **0** | **0** | **0** |
| cyclic_garbage | **0.476** | 0.485 | 0.941 |
| live_heap_churn | **5.853** | 10.097 | 17.906 |
| short_lived | **0** | **0** | **0** |

| case, collections | default | incremental | epoch |
|---|---|---|---|
| alloc_throughput | **0** | **0** | **0** |
| cyclic_garbage | 250 | 250 | **211** |
| live_heap_churn | 130 | **97** | 391 |
| short_lived | **0** | **0** | **0** |

