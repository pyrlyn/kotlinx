#!/bin/bash
# Prepares a private mutant tree in $MUT_DIR from the checkout $MUT_ROOT: real copies of compiler/gc and
# compiler/runtime (run.sh and newkotlinc resolve the GC dir relative to the runtime), the prebuilt compiler
# symlinked, everything else untouched. The checkout itself is never written.
set -e
mkdir -p "$MUT_DIR/compiler/build"
cp -R "$MUT_ROOT/compiler/gc" "$MUT_ROOT/compiler/runtime" "$MUT_DIR/compiler/"
ln -sf "$MUT_ROOT/compiler/build/newkotlinc.kexe" "$MUT_DIR/compiler/build/newkotlinc.kexe"
ln -sf "$MUT_ROOT/bench" "$MUT_DIR/bench"; ln -sf "$MUT_ROOT/mise.toml" "$MUT_DIR/mise.toml"
