# bigheap (median of 5 runs; 2026-10-30 21:55 EET; commit d9c3976)

Columns: default GC, epoch-base = #11 gc/epoch, epoch = this branch at d9c3976 (bounded queue, mmap slabs). Median of 5; machine load avg 40-60 on 16 cores.

| max GC pause ms | default | epoch-base | epoch |
|---|---|---|---|
| bigheap | 16.764 | 7.049 | **1.926** |

| peak RSS MiB | default | epoch-base | epoch |
|---|---|---|---|
| bigheap | **60.7** | 252.7 | 219 |

