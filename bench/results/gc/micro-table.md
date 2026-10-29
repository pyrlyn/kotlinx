| case, ms | default | rc | boehm |
|---|---|---|---|
| alloc_throughput | **79.317** | 79.924 | 179.028 |
| cyclic_garbage | 55.892 | **15.537** | 300.967 |
| live_heap_churn | 480.606 | **30.827** | 1375.18 |
| short_lived | 77.093 | **76.331** | 135.164 |

| case, peak RSS MiB | default | rc | boehm |
|---|---|---|---|
| alloc_throughput | **2.2** | **2.2** | 3 |
| cyclic_garbage | 4.4 | 125 | **3.2** |
| live_heap_churn | **25.7** | 166.9 | 41.1 |
| short_lived | **2.2** | **2.2** | 3 |

| case, total GC pause ms | default | rc | boehm |
|---|---|---|---|
| alloc_throughput | **0** | **0** | 0.098 |
| cyclic_garbage | 44.903 | **0** | 262.682 |
| live_heap_churn | 450.553 | **0** | 1183.32 |
| short_lived | **0** | **0** | 0.094 |

| case, max GC pause ms | default | rc | boehm |
|---|---|---|---|
| alloc_throughput | **0** | **0** | 0.098 |
| cyclic_garbage | 0.502 | **0** | 0.135 |
| live_heap_churn | 5.741 | **0** | 15.71 |
| short_lived | **0** | **0** | 0.094 |

| case, collections | default | rc | boehm |
|---|---|---|---|
| alloc_throughput | **0** | **0** | 2 |
| cyclic_garbage | 250 | **0** | 5232 |
| live_heap_churn | 130 | **0** | 110 |
| short_lived | **0** | **0** | 2 |

