# Memory regions vs main (feat/memory-regions)

nkbench method (warm-up + 5 runs, mean of middle 3), same session, Kotlin Native from the same session. Load avg ~31-37 (Mac busy). ms, lower is better.

| case | main | regions | delta | Kotlin Native |
|---|---|---|---|---|
| sum | **3.7** | 3.8 | +3.0% | 5.7 |
| alloc | **72.9** | 73.8 | +1.2% | 226.7 |
| closures | **83.3** | 83.9 | +0.7% | 555.5 |
| cycles | 57.6 | 51.0 | -11.4% | **19.7** |
| dispatch | **94.9** | 95.7 | +0.8% | 95.4 |
| file | **51.9** | 52.4 | +0.9% | 52.5 |
| free | 50.2 | **49.7** | -0.9% | 120.2 |
| hashmap | 74.0 | **72.4** | -2.1% | 252.4 |
| lru | 1016.8 | 1013.4 | -0.3% | **179.3** |
| matmul | **17.7** | 17.7 | +0.2% | 42.3 |
| nbody | **72.4** | 72.6 | +0.3% | 133.7 |
| recursion | 6.1 | 6.1 | -0.2% | **6.0** |
| refpass | 147.0 | 147.0 | -0.1% | **130.8** |
| sort | 63.1 | 63.6 | +0.8% | **61.5** |
| strings | **32.0** | 32.6 | +1.7% | 132.4 |
| trees | 145.0 | **143.1** | -1.3% | 241.2 |
