# pitx

Mutation testing on the LLVM IR emitted by KotlinX (like PIT, on IR instead of JVM bytecode). Written in Zig 0.17 (std only).
Design: [DESIGN.md](DESIGN.md).

```
cd pitx && zig build                            # -> pitx/zig-out/bin/pitx
pitx/zig-out/bin/pitx some.ll                   # standalone IR, run with lli
pitx/run_kt.sh compiler/tests/loop_labels.kt    # a golden test: compile, mutate, relink, compare with .out
cd pitx && zig build test                       # pitx's own tests (needs llvm-as and lli from the repo's mise LLVM tools)
```
