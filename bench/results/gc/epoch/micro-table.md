| case, ms | default | incremental | epoch |
|---|---|---|---|
| alloc_throughput | 95.898 | 80.548 | **80.38** |
| cyclic_garbage | 64.364 | **59.238** | 144.532 |
| live_heap_churn | 532.686 | **438.288** | 563.18 |
| short_lived | 84.421 | 77.041 | **76.759** |

| case, peak RSS MiB | default | incremental | epoch |
|---|---|---|---|
| alloc_throughput | **2.2** | **2.2** | **2.2** |
| cyclic_garbage | 4.4 | **3.7** | 62 |
| live_heap_churn | **25.6** | 32.3 | 49.2 |
| short_lived | **2.2** | **2.2** | **2.2** |

| case, total GC pause ms | default | incremental | epoch |
|---|---|---|---|
| alloc_throughput | **0** | **0** | 0.03 |
| cyclic_garbage | **50.264** | 50.569 | 110.431 |
| live_heap_churn | 472.611 | **417.411** | 431.254 |
| short_lived | **0** | **0** | 0.038 |

| case, max GC pause ms | default | incremental | epoch |
|---|---|---|---|
| alloc_throughput | **0** | **0** | 0.03 |
| cyclic_garbage | 0.533 | **0.463** | 12.795 |
| live_heap_churn | **5.338** | 8.569 | 20.615 |
| short_lived | **0** | **0** | 0.038 |

| case, collections | default | incremental | epoch |
|---|---|---|---|
| alloc_throughput | **0** | **0** | **0** |
| cyclic_garbage | 250 | 250 | **228** |
| live_heap_churn | 130 | 97 | **64** |
| short_lived | **0** | **0** | **0** |

