#!/usr/bin/env python3
"""Deterministic ~50 MB text file for the `file` case: data/words.txt."""
import os
os.makedirs(os.path.join(os.path.dirname(__file__), "data"), exist_ok=True)
path = os.path.join(os.path.dirname(__file__), "data", "words.txt")
target = 50 * 1024 * 1024
s = 12345
letters = b"abcdefghijklmnopqrstuvwxyz"
out = bytearray()
while len(out) < target:
    s = (s * 6364136223846793005 + 1442695040888963407) & 0xFFFFFFFFFFFFFFFF
    nwords = 1 + (s >> 33) % 12
    for w in range(nwords):
        s = (s * 6364136223846793005 + 1442695040888963407) & 0xFFFFFFFFFFFFFFFF
        ln = 1 + (s >> 35) % 9
        out += bytes(letters[(s >> (8 + 3 * k)) % 26] for k in range(ln))
        out += b" " if w + 1 < nwords else b"\n"
with open(path, "wb") as f:
    f.write(out)
print(path, len(out))
