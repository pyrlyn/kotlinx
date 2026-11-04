#!/usr/bin/env python3
"""Fetches the reference tests (upstream Kotlin box tests the compiler does not fully pass yet) into a directory.

list.txt: `<group> <path in JetBrains/kotlin>` per line; group `ctx` (expected-identifier / context / callable
reference tests) or `pcla` (builder-inference tests). Files are read at the pinned commit of the upstream import
(compiler/tests/upstream/import.py) and written as `<dest>/<group>/<path below testData/codegen/, '/' -> '_'>`, the
names the reports use. `extra/` holds five small local repros, copied to `<dest>/`.

  python3 compiler/tests/reference/fetch.py [dest]        # default ~/Grok/upref
  python3 compiler/tests/upstream/run.py --nkc NKC dest/ctx/*.kt dest/pcla/*.kt dest/*.kt
"""
import os, re, shutil, sys, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))

def pinned():
    src = open(os.path.join(HERE, "..", "upstream", "import.py"), encoding="utf-8").read()
    return re.search(r'PINNED_COMMIT\s*=\s*"([0-9a-f]+)"', src).group(1)

def main():
    dest = os.path.expanduser(sys.argv[1] if len(sys.argv) > 1 else "~/Grok/upref")
    commit = pinned()
    n = 0
    for line in open(os.path.join(HERE, "list.txt"), encoding="utf-8"):
        if not line.strip():
            continue
        group, path = line.split()
        name = path.split("testData/codegen/", 1)[-1].replace("/", "_")
        out = os.path.join(dest, group, name)
        os.makedirs(os.path.dirname(out), exist_ok=True)
        if not os.path.exists(out):
            url = f"https://raw.githubusercontent.com/JetBrains/kotlin/{commit}/{path}"
            with urllib.request.urlopen(url, timeout=30) as r, open(out, "wb") as f:
                f.write(r.read())
        n += 1
    for f in sorted(os.listdir(os.path.join(HERE, "extra"))):
        shutil.copy(os.path.join(HERE, "extra", f), os.path.join(dest, f)); n += 1
    print(f"{n} reference tests in {dest} (JetBrains/kotlin @ {commit[:7]})")

if __name__ == "__main__":
    main()
