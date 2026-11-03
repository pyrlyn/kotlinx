# pitx

Mutation testing on the LLVM IR emitted by KotlinX (like PIT, on IR instead of JVM bytecode). Design: [DESIGN.md](DESIGN.md).

```
python3 pitx/pitx.py some.ll                    # standalone IR, run with lli
pitx/run_kt.sh compiler/tests/loop_labels.kt    # a golden test: compile, mutate, relink, compare with .out
python3 -m unittest discover -s pitx/tests      # pitx's own tests
```
