#!/usr/bin/env python3
"""Function names and code sizes of a wasm module that has a name section (newkotlinc --target=wasm with
NK_WASM_CC=compiler/tests/wasm/cc-names.sh). Usage: funcs.py module.wasm -> lines "<bytes> <name>", largest first."""
import sys


def leb(b, i):
    r = s = 0
    while True:
        x = b[i]; i += 1; r |= (x & 0x7f) << s; s += 7
        if x < 0x80: return r, i


def funcs(path):
    b = open(path, "rb").read()
    i, imported, bodies, names = 8, 0, [], {}
    while i < len(b):
        sid = b[i]; n, j = leb(b, i + 1); end = j + n
        if sid == 2:  # imports: count the imported functions (they come first in the function index space)
            cnt, k = leb(b, j)
            for _ in range(cnt):
                for _ in range(2): ln, k = leb(b, k); k += ln
                kind = b[k]; k += 1
                if kind == 0: imported += 1; _, k = leb(b, k)
                elif kind == 1: k += 1; f = b[k]; k += 1; _, k = leb(b, k); k = leb(b, k)[1] if f & 1 else k
                elif kind == 2: f = b[k]; k += 1; _, k = leb(b, k); k = leb(b, k)[1] if f & 1 else k
                elif kind == 3: k += 2
                else: k += 1
        elif sid == 10:
            cnt, k = leb(b, j)
            for _ in range(cnt): sz, k2 = leb(b, k); bodies.append(sz + k2 - k); k = k2 + sz
        elif sid == 0:
            ln, k = leb(b, j)
            if b[k:k + ln] == b"name":
                k += ln
                while k < end:
                    sub = b[k]; sl, k = leb(b, k + 1); se = k + sl
                    if sub == 1:
                        cnt, k = leb(b, k)
                        for _ in range(cnt):
                            idx, k = leb(b, k); ln2, k = leb(b, k); names[idx] = b[k:k + ln2].decode(errors="replace"); k += ln2
                    k = se
        i = end
    return sorted(((sz, names.get(imported + x, f"f{imported + x}")) for x, sz in enumerate(bodies)), reverse=True)


if __name__ == "__main__":
    for sz, name in funcs(sys.argv[1]): print(sz, name)
