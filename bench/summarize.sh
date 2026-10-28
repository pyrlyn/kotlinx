#!/bin/bash
# Mean of the middle 3 of 5 (drop min and max) per case/row; prints markdown tables.
# Uses the LAST 5 entries per case/row in results/runs.txt and results/cpu.txt.
cd "$(dirname "$0")"
python3 - <<'PY'
from collections import defaultdict, OrderedDict
def mid3(v):
    v = sorted(v)[-5:] if len(v) >= 5 else sorted(v)
    v = sorted(v); return sum(v[1:-1]) / len(v[1:-1]) if len(v) >= 3 else sum(v)/len(v)
runs = OrderedDict(); cpu = defaultdict(list); cs = defaultdict(set)
for l in open("results/runs.txt"):
    c, row, _, s, ns, rss, *_ = l.split()
    runs.setdefault((c, row), []).append((int(ns), int(rss))); cs[c].add(s)
try:
    for l in open("results/cpu.txt"):
        c, row, _, n, real, user, sys = l.split()
        cpu[(c, row)].append((float(user) + float(sys)) / float(real) * 100)
except FileNotFoundError: pass
cur = None
order = []
for (c, _) in runs:
    if c not in order: order.append(c)
for (c, row), v in sorted(runs.items(), key=lambda kv: order.index(kv[0][0])):
    v = v[-5:]
    if c != cur:
        cur = c; print(f"\n### {c}  (checksums {'match' if len(cs[c]) == 1 else 'DIFFER: ' + ', '.join(cs[c])})\n")
        print("| Participant | Time, ns | Peak RSS, MB | CPU, % |\n|---|---|---|---|")
    t = mid3([x[0] for x in v]); m = mid3([x[1] for x in v]) / 1048576
    cp = f"{mid3(cpu[(c,row)][-5:]):.1f}" if cpu[(c, row)] else "n/a"
    print(f"| {row} | {round(t):,} | {m:.2f} | {cp} |".replace(",", " "))
PY
