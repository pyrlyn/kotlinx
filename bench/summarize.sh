#!/bin/bash
# Mean of the middle 3 of 5 (drop min and max) per case/row; prints markdown tables.
# Uses the LAST 5 entries per case/row in $RESULTS/runs.txt and $RESULTS/cpu.txt (RESULTS default: results).
cd "$(dirname "$0")"
RESULTS=${RESULTS:-results} python3 - <<'PY'
import os
from collections import defaultdict, OrderedDict
RES = os.environ["RESULTS"]
def mid3(v):
    v = sorted(v)[-5:] if len(v) >= 5 else sorted(v)
    v = sorted(v); return sum(v[1:-1]) / len(v[1:-1]) if len(v) >= 3 else sum(v)/len(v)
runs = OrderedDict(); cpu = defaultdict(list); cs = defaultdict(set)
for l in open(RES + "/runs.txt"):
    c, row, _, s, ns, rss, *_ = l.split()
    runs.setdefault((c, row), []).append((int(ns), int(rss))); cs[c].add(s)
try:
    for l in open(RES + "/cpu.txt"):
        c, row, _, n, real, user, sys = l.split()
        cpu[(c, row)].append((float(user) + float(sys)) / float(real) * 100)
except FileNotFoundError: pass
# Metric directions: best value per case and metric is bold ("lower" or "higher" is better).
METRICS = [("Time, ns", "lower", lambda x: f"{round(x):,}".replace(",", " ")),
           ("Peak RSS, MB", "lower", lambda x: f"{x:.2f}"),
           ("CPU, %", "lower", lambda x: f"{x:.1f}")]
# Higher-is-better metrics (e.g. ("Throughput, ops/s", "higher", fmt)) are bolded on the maximum.
order = []
for (c, _) in runs:
    if c not in order: order.append(c)
for c in order:
    rows = []
    for (cc, row), v in runs.items():
        if cc != c: continue
        v = v[-5:]
        vals = [mid3([x[0] for x in v]), mid3([x[1] for x in v]) / 1048576,
                mid3(cpu[(c, row)][-5:]) if cpu[(c, row)] else None]
        rows.append((row, vals))
    print(f"\n### {c}  (checksums {'match' if len(cs[c]) == 1 else 'DIFFER: ' + ', '.join(cs[c])})\n")
    print("| Participant | " + " | ".join(f"{n} ({d} is better)" for n, d, _ in METRICS) + " |")
    print("|---|" + "---|" * len(METRICS))
    best = []
    for i, (_, d, fmt) in enumerate(METRICS):
        xs = [fmt(r[1][i]) for r in rows if r[1][i] is not None]
        num = [float(x.replace(" ", "")) for x in xs]
        best.append(None if not num else fmt((min if d == "lower" else max)(num)))
    for row, vals in rows:
        cells = []
        for i, (_, _, fmt) in enumerate(METRICS):
            if vals[i] is None: cells.append("n/a"); continue
            f = fmt(vals[i]); cells.append(f"**{f}**" if f == best[i] else f)
        print(f"| {row} | " + " | ".join(cells) + " |")
PY
