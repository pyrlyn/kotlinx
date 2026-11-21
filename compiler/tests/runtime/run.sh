#!/bin/bash
# Runtime unit tests (C, host compiler): kxfmt.h's number formatting against libc (fmt_test.c). Usage: run.sh [N random values]
cd "$(dirname "$0")"
out=$(mktemp -d); trap 'rm -rf "$out"' EXIT
${NK_CC:-cc} -O2 -std=c11 -Wall -Wno-strict-aliasing fmt_test.c -lm -o "$out/fmt_test" && "$out/fmt_test" "${1:-1000000}"
