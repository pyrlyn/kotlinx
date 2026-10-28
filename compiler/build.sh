#!/bin/bash
# Builds the New Kotlin compiler (newkotlinc) with Kotlin/Native into compiler/build/ (debug build;
# set NEWKOTLIN_KN_FLAGS=-opt for an optimized compiler binary).
set -e
cd "$(dirname "$0")"
KN=${KONANC:-$HOME/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.21/bin/kotlinc-native}
mkdir -p build
"$KN" src/*.kt -e newkotlin.main ${NEWKOTLIN_KN_FLAGS:-} -o build/newkotlinc
echo "built compiler/build/newkotlinc.kexe"
