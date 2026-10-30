# lru (median of 5 runs; 2026-10-30 21:55 EET; commit d9c3976)

Columns: default GC, epoch-base = #11 gc/epoch, epoch = this branch at d9c3976 (bounded queue, mmap slabs). Median of 5; machine load avg 40-60 on 16 cores.

| max GC pause ms | default | epoch-base | epoch |
|---|---|---|---|
| lru | 4.748 | 4.726 | **3.78** |

| peak RSS MiB | default | epoch-base | epoch |
|---|---|---|---|
| lru | **29.7** | 32.5 | 36 |

