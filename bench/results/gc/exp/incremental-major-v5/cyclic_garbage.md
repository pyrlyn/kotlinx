# cyclic_garbage (median of 3 runs; 2026-10-30 21:03 EET; commit 2f01431)

Columns: default GC, epoch-base = #11 gc/epoch, epoch = this branch at 7685dc8 (measured on the working tree of 2f01431 + this change; machine load avg 40-60 on 16 cores, other jobs running).

| max GC pause ms | default | epoch-base | epoch |
|---|---|---|---|
| cyclic_garbage | **0.46** | 0.912 | 1.724 |

| peak RSS MiB | default | epoch-base | epoch |
|---|---|---|---|
| cyclic_garbage | **4.4** | 95.8 | 24.4 |

