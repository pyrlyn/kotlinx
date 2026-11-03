| case, ms | default | incremental | epoch | boehm |
|---|---|---|---|---|
| alloc_throughput | 80.606 | **80.334** | 81.087 | 179.685 |
| big_live_churn | 1736.35 | 1921.04 | **734.452** | 1300.14 |
| cyclic_garbage | **50.582** | 52.111 | 156.733 | 300.442 |
| huge_live_churn | 18405.5 | 18682.8 | **1433.15** | 2752.61 |
| live_heap_churn | 532.758 | 482.894 | **462.14** | 1354.93 |
| short_lived | **77.5** | 77.625 | 77.647 | 134.191 |
| wide_graph_churn | 100030 | 193921 | **3914.82** | 8188.73 |

| case, peak RSS MiB | default | incremental | epoch | boehm |
|---|---|---|---|---|
| alloc_throughput | **2.2** | **2.2** | **2.2** | 3.1 |
| big_live_churn | **144.6** | 144.7 | 201.7 | 485.4 |
| cyclic_garbage | 4.4 | 3.7 | 22.6 | **3.4** |
| huge_live_churn | 650 | **649.7** | 690.7 | 1867.6 |
| live_heap_churn | **24** | 35.8 | 37.5 | 40.5 |
| short_lived | **2.2** | **2.2** | **2.2** | 3.1 |
| wide_graph_churn | 725.5 | **725.4** | 850.1 | 2097.2 |

| case, total GC pause ms | default | incremental | epoch | boehm |
|---|---|---|---|---|
| alloc_throughput | **0** | **0** | **0** | 0.09 |
| big_live_churn | 2028.44 | 1900.79 | **697.102** | 1216.32 |
| cyclic_garbage | 43.906 | **42.168** | 140.897 | 220.57 |
| huge_live_churn | 20429.6 | 21166.2 | **1099.59** | 2702.46 |
| live_heap_churn | 475.641 | 456.969 | **412.666** | 1114.1 |
| short_lived | **0** | **0** | **0** | 0.117 |
| wide_graph_churn | 98541 | 189749 | **2679.3** | 6788.21 |

| case, p50 GC pause ms | default | incremental | epoch | boehm |
|---|---|---|---|---|
| alloc_throughput | **0** | **0** | **0** | 0.09 |
| big_live_churn | 39.795 | 34.165 | **0.245** | 6.743 |
| cyclic_garbage | 0.107 | 0.108 | 0.536 | **0.045** |
| huge_live_churn | 189.181 | 218.74 | **0.014** | 8.337 |
| live_heap_churn | 3.7 | 4.119 | **0.48** | 10.148 |
| short_lived | **0** | **0** | **0** | 0.117 |
| wide_graph_churn | 1087.47 | 2152.01 | **0.002** | 10.338 |

| case, p99 GC pause ms | default | incremental | epoch | boehm |
|---|---|---|---|---|
| alloc_throughput | **0** | **0** | **0** | 0.09 |
| big_live_churn | 176.018 | 213.8 | **3.005** | 206.891 |
| cyclic_garbage | 0.488 | 0.431 | 1.207 | **0.063** |
| huge_live_churn | 658.766 | 716.735 | **2.189** | 1008.94 |
| live_heap_churn | 5.45 | 10.035 | **1.486** | 13.626 |
| short_lived | **0** | **0** | **0** | 0.117 |
| wide_graph_churn | 1696.75 | 2315.01 | **5.66** | 2456.59 |

| case, max GC pause ms | default | incremental | epoch | boehm |
|---|---|---|---|---|
| alloc_throughput | **0** | **0** | **0** | 0.09 |
| big_live_churn | 176.018 | 213.8 | **9.467** | 206.891 |
| cyclic_garbage | 0.508 | 0.446 | 1.5 | **0.123** |
| huge_live_churn | 658.766 | 716.735 | **29.02** | 1008.94 |
| live_heap_churn | 5.635 | 10.765 | **1.847** | 15.185 |
| short_lived | **0** | **0** | **0** | 0.117 |
| wide_graph_churn | 1696.75 | 2315.01 | **165.626** | 2456.59 |

| case, collections | default | incremental | epoch | boehm |
|---|---|---|---|---|
| alloc_throughput | **0** | **0** | **0** | 2 |
| big_live_churn | 36 | 36 | 556 | **27** |
| cyclic_garbage | 250 | 250 | **223** | 5234 |
| huge_live_churn | 92 | 92 | 1056 | **27** |
| live_heap_churn | 130 | **102** | 429 | 118 |
| short_lived | **0** | **0** | **0** | 2 |
| wide_graph_churn | 97 | 97 | 843 | **28** |

