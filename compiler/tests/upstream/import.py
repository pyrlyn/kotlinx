#!/usr/bin/env python3
"""Import upstream Kotlin tests into compiler/tests/upstream/ (Python stdlib + git).

1. Sparse, blobless, shallow fetch of JetBrains/kotlin at PINNED_COMMIT into a cache dir.
2. Select candidate tests from SOURCES, skipping JVM-/JS-/Wasm-only tests listed in exclude-{jvm,js,wasm}.txt (path-based;
   not copied, not counted). JS/Wasm/Native tests stay candidates.
3. Run every candidate VERBATIM on New Kotlin (run.py) and copy only PASSING tests, byte-for-byte,
   into box/ and native/ (relative upstream paths preserved; golden .out files copied alongside).
   Tests are never modified: a test that would need changes to pass is not a pass.
4. Write manifest.json: commit, every candidate with status/category/detail.

Usage: import.py [--cache DIR] [--nkc PATH] [--jobs N] [--timeout S] [--cflags F]
Default cache: $KOTLIN_UPSTREAM_CACHE or <repo>/.upstream-cache/kotlin (git-ignored)
"""
import argparse, json, os, re, shutil, subprocess, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import run as R

PINNED_COMMIT = "19c36e179da4ff601be5043468aefb63dcb1f201"   # JetBrains/kotlin master, 2026-10-28
URL = "https://github.com/JetBrains/kotlin"
HERE = os.path.dirname(os.path.abspath(__file__))
# (upstream dir, tree prefix, exclude subdir regex)
SOURCES = [
    ("compiler/testData/codegen/box", "box/box", None),
    ("compiler/testData/codegen/boxInline", "box/boxInline", None),
    ("native/native.tests/testData", "native/native.tests",
     r"^(CExport|CInterop|ObjCExport|framework|interop|lldb|klib|caches|echoServer|compilerOutput|"
     r"oneStageCompilation|programName|scripts|testRunner|infrastructure|codegen/cinterop|codegen/fileCheck)(/|$)"),
    ("kotlin-native/backend.native/tests", "native/backend.native", None),
]

def sh(*cmd, cwd=None):
    subprocess.run(cmd, cwd=cwd, check=True)

def fetch(cache):
    if not os.path.isdir(os.path.join(cache, ".git")):
        os.makedirs(cache, exist_ok=True)
        sh("git", "init", "-q", cwd=cache)
        sh("git", "remote", "add", "origin", URL, cwd=cache)
    head = subprocess.run(["git", "rev-parse", "HEAD"], cwd=cache, capture_output=True, text=True).stdout.strip()
    sh("git", "sparse-checkout", "set", *[s for s, _, _ in SOURCES], cwd=cache)
    if head != PINNED_COMMIT:
        sh("git", "fetch", "-q", "--depth", "1", "--filter=blob:none", "origin", PINNED_COMMIT, cwd=cache)
        sh("git", "checkout", "-q", "--detach", PINNED_COMMIT, cwd=cache)
    sh("git", "sparse-checkout", "reapply", cwd=cache)

def candidates(cache):
    for src, prefix, excl in SOURCES:
        root = os.path.join(cache, src)
        if not os.path.isdir(root):
            continue
        ex = re.compile(excl) if excl else None
        for dp, _, fs in os.walk(root):
            rel_dir = os.path.relpath(dp, root)
            if ex and ex.match(rel_dir.replace(os.sep, "/")):
                continue
            for f in sorted(fs):
                if f.endswith(".kt"):
                    rel = os.path.normpath(os.path.join(rel_dir, f))
                    yield os.path.join(root, rel), os.path.join(prefix, rel), os.path.join(src, rel)

EXCLUDE_LISTS = ("jvm", "js", "wasm")  # exclude-<backend>.txt: tests only for other backends

def load_exclude(backend):
    """exclude-<backend>.txt: <backend>-only tests, one upstream path per line (a directory ending in '/' or a file)."""
    lines = open(os.path.join(HERE, f"exclude-{backend}.txt")).read().splitlines()
    return [l.strip() for l in lines if l.strip() and not l.lstrip().startswith("#")]

def excluded(uprel, excl):
    return any(uprel.startswith(e) if e.endswith("/") else uprel == e for e in excl)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", default=os.environ.get("KOTLIN_UPSTREAM_CACHE",
                    os.path.join(R.REPO, ".upstream-cache", "kotlin")))
    ap.add_argument("--nkc", default=os.environ.get("NKC", R.DEFAULT_NKC))
    ap.add_argument("--jobs", type=int, default=6)
    ap.add_argument("--timeout", type=float, default=10)
    ap.add_argument("--cflags", default=None)
    ap.add_argument("--no-fetch", action="store_true")
    a = ap.parse_args()
    if not a.no_fetch:
        fetch(a.cache)
    items, meta = [], {}
    excls = {b: load_exclude(b) for b in EXCLUDE_LISTS}
    n_excl = {b: 0 for b in EXCLUDE_LISTS}
    for up, tree, uprel in candidates(a.cache):
        hit = next((b for b in EXCLUDE_LISTS if excluded(uprel, excls[b])), None)
        if hit:
            n_excl[hit] += 1
            continue
        meta[tree] = {"upstream": uprel, "src": up}
        items.append((tree, up, None))
    for b in EXCLUDE_LISTS:
        print(f"excluded {n_excl[b]} {b}-only tests (exclude-{b}.txt)", file=sys.stderr)
    print(f"running {len(items)} candidates with {a.nkc}", file=sys.stderr)
    res = R.run_many(items, a.nkc, a.jobs, a.timeout, a.cflags)
    for sub in ("box", "native"):
        shutil.rmtree(os.path.join(HERE, sub), ignore_errors=True)
    tests, counts = {}, {}
    for tree in sorted(res):
        r, m = res[tree], meta[tree]
        st = r["status"]; counts[st] = counts.get(st, 0) + 1
        e = {"upstream": m["upstream"], "status": st, "detail": r.get("detail", "")}
        if "feature" in r:
            e["category"] = r["feature"]
        tests[tree] = e
        if st == "PASS":
            dst = os.path.join(HERE, tree)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copy(m["src"], dst)
            out = os.path.splitext(m["src"])[0] + ".out"
            if os.path.exists(out):
                shutil.copy(out, os.path.splitext(dst)[0] + ".out")
    json.dump({"repo": URL, "commit": PINNED_COMMIT, "sources": [s for s, _, _ in SOURCES],
               "excluded_jvm_only": n_excl["jvm"], "excluded_js_only": n_excl["js"],
               "excluded_wasm_only": n_excl["wasm"], "counts": counts, "tests": tests}, open(os.path.join(HERE, "manifest.json"), "w"), indent=1, sort_keys=True)
    print("counts:", counts)

if __name__ == "__main__":
    main()
