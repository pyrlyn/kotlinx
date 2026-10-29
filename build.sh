#!/bin/bash
# Builds the Kotlin-X compiler: compiler/build/newkotlinc.kexe (launcher: bin/newkotlinc).
exec "$(dirname "$0")/compiler/build.sh" "$@"
