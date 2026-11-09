#!/bin/bash
# NK_WASM_CC for compiler/tests/wasm/size.sh: `zig cc -target wasm32-wasi` without -s, so the module keeps its
# name section and funcs.py can list what got linked.
a=(); for x in "$@"; do [ "$x" = "-s" ] || a+=("$x"); done
exec "${ZIG:-zig}" cc -target wasm32-wasi "${a[@]}"
