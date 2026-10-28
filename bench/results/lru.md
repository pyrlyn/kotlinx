# lru: realistic cyclic structure (LRU cache)

Workload: LRU cache, capacity 50k, keys 0..200k (80% of requests hit the hot 40k keys), 3M get-or-load ops,
each entry owns a heap value. Live neighbours form prev<->next cycles; the final list is cyclic garbage.
Rust: Rc/RefCell + Weak prev; Zig: c_allocator create/destroy; K/N: tracing GC; New Kotlin: RC + cycle collector (mimalloc).
Checksum 322182091481 in all four. Method: warm-up + 5 runs, mean of the middle 3, same session.
Load avg 18.4 -> 20.1 (Mac busy). 2026-10-28.

| case | Rust | Zig | Kotlin Native | New Kotlin |
|---|---|---|---|---|
| lru, ms | 149.0 | **85.3** | 177.9 | 957.8 |
| cycles (synthetic), ms | 83.8 | 61.7 | **18.2** | 57.0 |
