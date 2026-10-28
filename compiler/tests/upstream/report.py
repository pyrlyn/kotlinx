#!/usr/bin/env python3
"""Write report.md from manifest.json (counts, unsupported-feature roadmap, bugs). Kept tests are verbatim upstream."""
import collections, json, os
HERE = os.path.dirname(os.path.abspath(__file__))
m = json.load(open(os.path.join(HERE, "manifest.json")))
t = m["tests"]
L = [f"# Upstream Kotlin tests on New Kotlin\n", f"Upstream: {m['repo']} @ `{m['commit']}`  ",
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
L += ["", "Scope: all backends except JVM-only. Excluded as JVM-only: "
      f"{m.get('excluded_jvm_only', 0)} tests (not copied, not counted). JS/Wasm/Native tests are kept.\n",
      "Targets: only `--target=native` exists. Tests whose `TARGET_BACKEND` names other non-JVM backends only (JS/Wasm) "
      "are kept as candidates and reported as SKIPPED_TARGET until those targets exist.\n",
      "All kept tests are byte-for-byte copies of upstream; no test is adapted or patched.\n",
      "Mechanism: JVM-only tests are excluded by path via `exclude-jvm.txt` in our harness (a per-backend list: a future JVM backend run would not apply it) (directories ending in `/` "
      "or single files, relative to the upstream repo); test files are never edited. Tests that New Kotlin's harness "
      "cannot run as-is (directives such as `IGNORE_BACKEND: NATIVE`, non-JVM `TARGET_BACKEND`, multi-file/module, "
      "reflection, coroutines) are still counted, as N/A, by `run.py`.\n"]
L += ["", "## Bugs (MISCOMPILE / COMPILER_CRASH)\n", "Minimal repros in `bugs/`:\n"]
for f in sorted(os.listdir(os.path.join(HERE, "bugs"))):
    first = open(os.path.join(HERE, "bugs", f)).readline().strip("/ \n")
    L.append(f"- `bugs/{f}` — {first}")
L += ["", "Affected upstream tests:\n"]
for k, v in sorted(t.items()):
    if v["status"] in ("MISCOMPILE", "COMPILER_CRASH"):
        L.append(f"- {v['status']} `{k}` — {v['detail'].splitlines()[0][:120]}")
open(os.path.join(HERE, "report.md"), "w").write("\n".join(L) + "\n")
