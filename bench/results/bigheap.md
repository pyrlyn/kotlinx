# bigheap: large live heap of deep cyclic graphs + acyclic traffic

Workload: 2000 live graphs, each a ring of 200 nodes; every node owns a payload and points back to its graph
(~800k live objects, all in cycles). 200 rounds x 50 replacements: a random graph is replaced by a new one
(the old ring becomes cyclic garbage), the new graph is walked 50 steps, and a short-lived acyclic chain of
100 leaves is built and summed. ~2.4M ring nodes allocated in total.
Rust: Rc/RefCell + Weak owner, rings unwound by hand; Zig: c_allocator create/destroy; K/N: tracing GC;
New Kotlin: RC + cycle collector (main, mimalloc). Checksum 3721955867440 in all four.
Method: warm-up + 5 runs, mean of the middle 3, rows interleaved per case, same session.
Load avg 27.0 -> 35.3 (Mac busy). 2026-10-28.

| case, ms | Rust | Zig | Kotlin Native | New Kotlin |
|---|---|---|---|---|
| alloc | 165.4 | 155.1 | 237.6 | **74.7** |
| free | 107.4 | 91.9 | 131.2 | **51.5** |
| refpass | 52.0 | **27.9** | 131.4 | 145.1 |
| cycles | 82.9 | 62.2 | **18.3** | 57.5 |
| trees | 303.3 | 279.4 | 228.6 | **144.2** |
| bigheap | 117.6 | **98.2** | 100.8 | 141.0 |
