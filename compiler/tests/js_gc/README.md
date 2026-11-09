# --target=js collector choice

Without `--gc`, `--target=js` links the `compact` collector when the program allocates an object of a possibly
cyclic class (`rt_alloc_trace` in the program IR; see `allocatesCyclic` in `compiler/src/driver/JsDriver.kt`), else
plain reference counting (`rc`). `compiler/test.sh` builds each file here with `LL2JS_PRETTY=1` and checks the
output and the linked collector: `cyclic_*.kt` must link compact, `acyclic_*.kt` rc.
