# --target=js collector choice

Without `--gc`, `--target=js` links the `compact` collector when the program allocates an object of a possibly
cyclic class (`rt_alloc_trace` in the program IR; see `allocatesCyclic` in `compiler/src/driver/JsDriver.kt`), else
plain reference counting (`rc`). `compiler/test.sh` builds each file here with `LL2JS_PRETTY=1` and checks the
output and the linked collector: `cyclic_*.kt` must link compact, `acyclic_*.kt` rc.

`acyclic_counts.kt` also covers the 32-bit collection counts and lengths of `--target=js` (`rt_cnt` in
`compiler/runtime/runtime.c`): list growth, map rehash and removal, long builders and shared references.

`acyclic_locals.kt` covers JsGen variable reuse (`allocateLocals` in `compiler/src/backend/js/JsGen.kt`): phi swaps
and rotations in loops, Long values, and booleans and doubles live across branches.

`acyclic_fields.kt` covers constant-offset addresses (`constGep` in `JsGen.kt`): fields written in one block and
read in others, nested objects, Long and Double fields.

`acyclic_control.kt` covers the label peephole (`open`/`close` in `JsGen.kt`): labeled break and continue in
nested loops, when chains, early returns, do-while.
