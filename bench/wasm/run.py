#!/usr/bin/env python3
"""Runs the modules built by wasm/build.sh under Node and writes a comparison table.

Per case: one warm-up run per variant, then RUNS runs round-robin over the variants (kx, kx-opt, kx-c, kw, kw-opt).
The KotlinX / Kotlin/Wasm ratio uses the default build (Zig collector: kx, kx-opt); kx-c is shown for comparison.
Kernel time = the program's own `nanos=` (same clock source for both: monotonic), median of RUNS.
Wall = whole `node` process (startup, compile/instantiate, run), median. Peak RSS from /usr/bin/time -l, median.
Size = main.wasm bytes and gzip -9 bytes (kw also needs its 659-byte main.mjs loader; kx uses wasm/run.mjs).
All variants of a case must print the same checksum, else the case is marked.
Usage: wasm/run.py [RUNS] (default 5); CASES="alloc sort" limits the cases. Writes results/wasm/{runs.txt,table.md}.
"""
import gzip, os, re, statistics, subprocess, sys, time

BENCH = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.chdir(BENCH)
RUNS = int(sys.argv[1]) if len(sys.argv) > 1 else 5
# Kotlin/Wasm's deep(100_000) overflows V8's default stack (kw-opt needs ~7 MB, kw more): every variant runs with a
# 64 MB process stack limit (macOS hard limit) and a matching V8 limit.
NODE = ["sh", "-c", 'ulimit -s 65520 && exec node --no-warnings --stack-size=60000 "$@"', "node"]
VARIANTS = ["kx", "kx-opt", "kx-c", "kw", "kw-opt"]
LABEL = {"kx": "KotlinX → LLVM → wasm32", "kx-opt": "KotlinX + wasm-opt -O3", "kx-c": "KotlinX, C collector",
         "kw": "Kotlin/Wasm (DCE)", "kw-opt": "Kotlin/Wasm + Binaryen (Gradle prod)"}
cases = os.environ.get("CASES", "").split() or sorted(os.listdir("wasm/out"), key=lambda c: (c != "sum", c))


def cmd(case, v):
    d = f"wasm/out/{case}/{v}"
    if not os.path.exists(f"{d}/main.wasm"):
        return None
    return NODE + (["wasm/run.mjs", f"{d}/main.wasm"] if v.startswith("kx") else [f"{d}/main.mjs"])


def run(c):
    t0 = time.perf_counter()
    p = subprocess.run(["/usr/bin/time", "-l"] + c, capture_output=True, text=True)
    wall = time.perf_counter() - t0
    if p.returncode != 0:
        raise RuntimeError(f"{' '.join(c)} failed: {p.stderr[-400:]}")
    ns = int(re.search(r"nanos=(\d+)", p.stdout).group(1))
    chk = re.search(r"(?:checksum|sum)=(-?\d+)", p.stdout).group(1)
    rss = int(re.search(r"(\d+)\s+maximum resident set size", p.stderr).group(1))
    return ns, wall, rss, chk


def fmt_ms(ns): return f"{ns / 1e6:,.1f}"
def fmt_kb(b): return f"{b / 1024:,.1f}"


os.makedirs("results/wasm", exist_ok=True)
raw = open("results/wasm/runs.txt", "a")
node_v = subprocess.run(["node", "--version"], capture_output=True, text=True).stdout.strip()
rows = {}
for case in cases:
    vs = [v for v in VARIANTS if cmd(case, v)]
    for v in vs:
        run(cmd(case, v))  # warm-up (file cache, node compile cache)
    res = {v: [] for v in vs}
    for i in range(RUNS):
        for v in vs:
            r = run(cmd(case, v))
            res[v].append(r)
            raw.write(f"{case} {v} {i} {r[3]} {r[0]} {r[2]} {r[1]:.4f}\n")
    chks = {r[3] for v in vs for r in res[v]}
    rows[case] = {}
    for v in vs:
        w = open(f"wasm/out/{case}/{v}/main.wasm", "rb").read()
        rows[case][v] = dict(ns=statistics.median(r[0] for r in res[v]), wall=statistics.median(r[1] for r in res[v]),
                             rss=statistics.median(r[2] for r in res[v]), size=len(w), gz=len(gzip.compress(w, 9)),
                             ok=len(chks) == 1)
    print(case, {v: fmt_ms(rows[case][v]["ns"]) for v in vs}, "checksums match" if len(chks) == 1 else f"MISMATCH {chks}",
          flush=True)
raw.close()

out = [f"# Kotlin → wasm: KotlinX (LLVM, linear memory) vs Kotlin/Wasm (WasmGC)\n",
       f"Node {node_v} (V8), {RUNS} runs round-robin after a warm-up, medians. Lower is better everywhere; "
       f"**bold** = best per row.\n"]


def table(title, key, f, note=""):
    out.append(f"\n## {title}\n{note}\n| Case | " + " | ".join(LABEL[v] for v in VARIANTS) + " | KotlinX / Kotlin/Wasm |\n")
    out.append("|---|" + "---|" * (len(VARIANTS) + 1) + "\n")
    for case, r in rows.items():
        best = min(r[v][key] for v in r)
        cells = [("**" + f(r[v][key]) + "**" if r[v][key] == best else f(r[v][key])) if v in r else "n/a" for v in VARIANTS]
        ratio = ""
        if "kx-opt" in r and "kw-opt" in r:
            ratio = f"{min(r['kx'][key], r['kx-opt'][key]) / min(r['kw'][key], r['kw-opt'][key]):.2f}×"
        mark = "" if all(x["ok"] for x in r.values()) else " ⚠ checksum mismatch"
        out.append(f"| {case}{mark} | " + " | ".join(cells) + f" | {ratio} |\n")


table("Kernel time, ms", "ns", fmt_ms, "Time the program measures around its kernel (`nanos=`).\n")
table("Process wall time, ms", "wall", lambda s: f"{s * 1000:,.0f}", "Whole `node` process, incl. startup and wasm compile.\n")
table("Peak RSS, MB", "rss", lambda b: f"{b / 1048576:,.1f}", "Whole `node` process.\n")
table("main.wasm size, KB", "size", fmt_kb)
table("main.wasm gzip -9, KB", "gz", fmt_kb)
open("results/wasm/table.md", "w").write("".join(out))
print("wrote results/wasm/table.md")
