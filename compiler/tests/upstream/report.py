#!/usr/bin/env python3
"""Write report.md from manifest.json (counts, unsupported-feature roadmap, bugs). Kept tests are verbatim upstream."""
import collections, json, os
HERE = os.path.dirname(os.path.abspath(__file__))
m = json.load(open(os.path.join(HERE, "manifest.json")))
t = m["tests"]
L = [f"# Upstream Kotlin tests on Kotlin-X compiler\n", f"Upstream: {m['repo']} @ `{m['commit']}`  ",
     f"Sources: {', '.join(m['sources'])}\n", "## Counts\n", "| status | tests |", "|---|---|"]
for k, v in sorted(m["counts"].items(), key=lambda x: -x[1]):
    L.append(f"| {k} | {v} |")
L += ["", f"Kept in tree (re-runnable with `run.py`): {sum(1 for v in t.values() if v['status']=='PASS')}\n",
      "## Unsupported features (roadmap, by test count)\n", "| feature | tests |", "|---|---|"]
for k, v in collections.Counter(v.get("category") for v in t.values() if v["status"] == "UNSUPPORTED").most_common(40):
    L.append(f"| {k} | {v} |")
L += ["", "## N/A reasons\n", "| reason | tests |", "|---|---|"]
for k, v in collections.Counter(v["detail"] for v in t.values() if v["status"] == "N/A").most_common():
    L.append(f"| {k} | {v} |")
L += ["", "Scope: Native target only. Excluded by path (not copied, not counted): "
      f"JVM-only {m.get('excluded_jvm_only', 0)}, JS-only {m.get('excluded_js_only', 0)}, "
      f"Wasm-only {m.get('excluded_wasm_only', 0)} tests.\n",
      "Targets: only `--target=native` exists. Any remaining test whose `TARGET_BACKEND` excludes NATIVE is "
      "reported as SKIPPED_TARGET (the exclude lists should keep this at 0).\n",
      "All kept tests are byte-for-byte copies of upstream; no test is adapted or patched.\n",
      "Mechanism: per-backend path lists in our harness: `exclude-jvm.txt`, `exclude-js.txt`, `exclude-wasm.txt` "
      "(directories ending in `/` or single files, relative to the upstream repo; a future backend of that kind would not apply its own list). "
      "Test files are never edited. Tests that Kotlin-X compiler's harness cannot run as-is (directives such as "
      "`IGNORE_BACKEND: NATIVE`, multi-file/module, reflection, coroutines) are still counted, as N/A, by `run.py`.\n"]
L += ["", "## Bugs (MISCOMPILE / COMPILER_CRASH)\n", "Minimal repros in `bugs/`:\n"]
for f in sorted(os.listdir(os.path.join(HERE, "bugs"))):
    first = open(os.path.join(HERE, "bugs", f)).readline().strip("/ \n")
    L.append(f"- `bugs/{f}` — {first}")
L += ["", "Affected upstream tests:\n"]
for k, v in sorted(t.items()):
    if v["status"] in ("MISCOMPILE", "COMPILER_CRASH"):
        L.append(f"- {v['status']} `{k}` — {v['detail'].splitlines()[0][:120]}")
open(os.path.join(HERE, "report.md"), "w").write("\n".join(L) + "\n")
