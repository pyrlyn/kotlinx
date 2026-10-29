#!/usr/bin/env python3
"""Appends one benchmark result to bench/results/<suite>/<bench>.json (one file per benchmark).

  record.py <suite> <bench> --run NAME [--gc GC] [--ref REF] [--date ISO] [--commit SHA] [--note TEXT] metric=value ...

Each file is {"suite", "bench", "runs": [...]}; a run records date, commit (+ dirty flag), ref, machine,
OS, toolchain (clang, opt), GC and the metrics. Values default to the current checkout / machine."""
import argparse, datetime, json, os, platform, subprocess

HERE = os.path.dirname(os.path.abspath(__file__))

def sh(*cmd):
    try: return subprocess.run(cmd, capture_output=True, text=True, timeout=10).stdout.strip()
    except Exception: return ""

def first(s): return s.splitlines()[0] if s else ""
def version_line(s): return next((l.strip() for l in s.splitlines() if "version" in l), first(s))

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("suite"); ap.add_argument("bench")
    ap.add_argument("--run", required=True); ap.add_argument("--gc", default=os.environ.get("NEWKOTLIN_GC", "default"))
    ap.add_argument("--ref"); ap.add_argument("--date"); ap.add_argument("--commit"); ap.add_argument("--note"); ap.add_argument("--load")
    ap.add_argument("metrics", nargs="*")
    a = ap.parse_args()
    metrics = {}
    for m in a.metrics:
        k, v = m.split("=", 1)
        try: f = float(v); metrics[k] = int(f) if f.is_integer() and "." not in v else f
        except ValueError: metrics[k] = v
    commit = a.commit or sh("git", "-C", HERE, "rev-parse", "HEAD")
    cc = os.environ.get("NK_CC") or ("/usr/bin/clang" if os.path.exists("/usr/bin/clang") else "clang")
    opt = os.environ.get("NK_OPT") or sh("mise", "which", "opt") or "opt"
    run = {
        "run": a.run,
        "date": a.date or datetime.datetime.now().astimezone().isoformat(timespec="seconds"),
        "commit": commit,
        "dirty": bool(sh("git", "-C", HERE, "status", "--porcelain", "--untracked-files=no")) if not a.commit else False,
        "ref": a.ref,
        "gc": a.gc,
        "machine": {"model": sh("sysctl", "-n", "hw.model"), "cpu": sh("sysctl", "-n", "machdep.cpu.brand_string") or platform.processor(),
                    "os": f"{platform.system()} {platform.mac_ver()[0] or platform.release()}", "load": a.load or sh("sysctl", "-n", "vm.loadavg")},
        "toolchain": {"clang": first(sh(cc, "--version")), "opt": version_line(sh(opt, "--version")) or opt},
        "metrics": metrics,
    }
    if a.note: run["note"] = a.note
    d = os.path.join(HERE, "results", a.suite); os.makedirs(d, exist_ok=True)
    p = os.path.join(d, a.bench + ".json")
    data = json.load(open(p)) if os.path.exists(p) else {"suite": a.suite, "bench": a.bench, "runs": []}
    data["runs"].append(run)
    with open(p, "w") as f: json.dump(data, f, indent=1); f.write("\n")

if __name__ == "__main__": main()
