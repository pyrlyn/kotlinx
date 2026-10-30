# lru (median of 3 runs; 2026-10-30 21:04 EET; commit 2f01431)

Columns: default GC, epoch-base = #11 gc/epoch, epoch = this branch at 7685dc8 (measured on the working tree of 2f01431 + this change; machine load avg 40-60 on 16 cores, other jobs running).

| max GC pause ms | default | epoch-base | epoch |
|---|---|---|---|
| lru | 3.486 | 3.636 | **2.193** |

| peak RSS MiB | default | epoch-base | epoch |
|---|---|---|---|
| lru | **29.7** | 32.9 | 41.9 |

