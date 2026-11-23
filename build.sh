#!/bin/bash
# Builds the ZKotlin compiler: compiler/build/newkotlinc.kexe (launcher: bin/newkotlinc).
exec "$(dirname "$0")/compiler/build.sh" "$@"
