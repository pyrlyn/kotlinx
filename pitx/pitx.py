#!/usr/bin/env python3
"""pitx: mutation testing on LLVM IR emitted by the KotlinX compiler (PIT-style, IR instead of JVM bytecode).

Library + CLI. Works only on copies of IR text; never touches sources. Stdlib only.
See DESIGN.md.
"""
from __future__ import annotations
import argparse, dataclasses, json, os, re, shutil, subprocess, sys, tempfile
from concurrent.futures import ThreadPoolExecutor

def _read(path: str) -> str:
    with open(path) as f:
        return f.read()


# ---------------------------------------------------------------- operators

ICMP_FLIP = {"eq": "ne", "ne": "eq", "slt": "sge", "sge": "slt", "sgt": "sle", "sle": "sgt",
             "ult": "uge", "uge": "ult", "ugt": "ule", "ule": "ugt"}
ARITH_SWAP = {"add": "sub", "sub": "add"}

_ICMP_RE = re.compile(r"(=\s*icmp\s+)(eq|ne|slt|sge|sgt|sle|ult|uge|ugt|ule)\b")
# `add`/`sub` with optional nuw/nsw flags; integer only (fadd/fsub do not match because of \b after '=').
_ARITH_RE = re.compile(r"(=\s*)(add|sub)\b((?:\s+(?:nuw|nsw))*)")


@dataclasses.dataclass(frozen=True)
class Mutant:
    id: int
    op: str          # operator name
    line: int        # 1-based line in the IR
    function: str    # enclosing function name
    original: str    # original fragment
    replacement: str
    loc: str = ""    # Kotlin source location via !dbg, if any

    def describe(self) -> str:
        where = f" ({self.loc})" if self.loc else ""
        return f"#{self.id} {self.op} @{self.function}:{self.line} '{self.original}' -> '{self.replacement}'{where}"


def _icmp(line: str):
    m = _ICMP_RE.search(line)
    if m:
        p = m.group(2)
        yield m.start(2), m.end(2), p, ICMP_FLIP[p]


def _arith(line: str):
    m = _ARITH_RE.search(line)
    if m:
        o = m.group(2)
        # nuw/nsw are dropped: they are poison-generating for the swapped op and would make the mutant UB.
        yield m.start(2), m.end(0), m.group(0)[m.start(2) - m.start(0):], ARITH_SWAP[o]


OPERATORS = {"icmp-flip": _icmp, "arith-swap": _arith}

_DEFINE_RE = re.compile(r"^define\b.*?@([\w.$\"-]+)\(")
_DBG_RE = re.compile(r"!dbg\s+(!\d+)")
_DILOC_RE = re.compile(r"^(!\d+)\s*=.*DILocation\(line:\s*(\d+)")


def enumerate_sites(ir: str, ops=None, skip_functions: re.Pattern | None = None,
                    only_reachable: bool = False) -> list[Mutant]:
    """All mutation sites in function bodies, in IR order. only_reachable: skip functions not reachable
    from @main (e.g. the unused stdlib prelude every KotlinX module carries)."""
    ops = ops or list(OPERATORS)
    reach = reachable_functions(ir) if only_reachable else None
    lines = ir.split("\n")
    diloc = {m.group(1): m.group(2) for l in lines if (m := _DILOC_RE.match(l))}
    out, fn = [], None
    for i, line in enumerate(lines):
        if (d := _DEFINE_RE.match(line)):
            fn = d.group(1); continue
        if line.startswith("}"):
            fn = None; continue
        if fn is None or (skip_functions and skip_functions.search(fn)) or (reach is not None and fn not in reach):
            continue
        code = line.split(";", 1)[0]
        for op in ops:
            for s, e, orig, repl in OPERATORS[op](code):
                dm = _DBG_RE.search(line)
                loc = f"line {diloc[dm.group(1)]}" if dm and dm.group(1) in diloc else ""
                out.append(Mutant(len(out), op, i + 1, fn, orig, repl, loc))
    return out


_REF_RE = re.compile(r"@([\w.$-]+|\"[^\"]+\")")
_GLOBAL_RE = re.compile(r"^@([\w.$-]+|\"[^\"]+\")\s*=")


def reachable_functions(ir: str, roots=("main",)) -> set[str]:
    """Functions transitively referenced from `roots` (calls, function pointers, and globals such as
    vtables referenced by reachable code). Conservative: any textual @ref counts as an edge."""
    refs: dict[str, set[str]] = {}
    cur = None
    for line in ir.split("\n"):
        code = line.split(";", 1)[0]
        if (d := _DEFINE_RE.match(code)):
            cur = d.group(1); refs.setdefault(cur, set()); continue
        if cur is not None:
            if line.startswith("}"):
                cur = None
            else:
                refs[cur].update(_REF_RE.findall(code))
            continue
        if (g := _GLOBAL_RE.match(code)):
            refs.setdefault(g.group(1), set()).update(_REF_RE.findall(code[g.end():]))
    seen, todo = set(), [r for r in roots if r in refs]
    while todo:
        n = todo.pop()
        if n in seen:
            continue
        seen.add(n)
        todo.extend(r for r in refs.get(n, ()) if r not in seen)
    return seen


def apply(ir: str, m: Mutant) -> str:
    """Return a copy of `ir` with mutant `m` applied (exactly one textual site)."""
    lines = ir.split("\n")
    line = lines[m.line - 1]
    for s, e, orig, repl in OPERATORS[m.op](line.split(";", 1)[0]):
        if orig == m.original:
            lines[m.line - 1] = line[:s] + repl + line[e:]
            return "\n".join(lines)
    raise ValueError(f"site not found: {m.describe()}")

# ---------------------------------------------------------------- runner

CAUGHT, SURVIVED, TIMEOUT, INVALID = "caught", "survived", "timeout", "invalid"


def find_tool(name: str) -> str:
    env = os.environ.get("PITX_" + name.upper().replace("-", "_"))
    if env:
        return env
    try:
        opt = subprocess.run(["mise", "which", "opt"], capture_output=True, text=True,
                             cwd=os.path.dirname(os.path.abspath(__file__))).stdout.strip()
        if opt:
            # mise shims (.mise-bins) only list some tools; the real bin dir has all of them.
            for d in (os.path.dirname(opt), os.path.join(os.path.dirname(os.path.dirname(opt)), "bin")):
                if os.access(os.path.join(d, name), os.X_OK):
                    return os.path.join(d, name)
    except FileNotFoundError:
        pass
    return shutil.which(name) or name


def verify(ll_path: str) -> tuple[bool, str]:
    """llvm-as parses and runs the verifier; non-zero exit means invalid IR."""
    r = subprocess.run([find_tool("llvm-as"), ll_path, "-o", os.devnull], capture_output=True, text=True)
    return r.returncode == 0, r.stderr.strip()


@dataclasses.dataclass
class Result:
    mutant: Mutant
    status: str
    detail: str = ""


class LliHarness:
    """Run a standalone IR module with `lli`; expected = (stdout, exit code) of the unmutated module."""
    def __init__(self, timeout: float = 10.0):
        self.timeout = timeout

    def run(self, ll_path: str, workdir: str) -> tuple[str, int]:
        r = subprocess.run([find_tool("lli"), ll_path], capture_output=True, text=True, errors="replace", timeout=self.timeout)
        return r.stdout, r.returncode


class LinkHarness:
    """Link a KotlinX-emitted module with the runtime + GC via clang (as the driver does) and run the binary."""
    def __init__(self, runtime: str, gc_dir: str, gc: str = "default", cc: str = "/usr/bin/clang",
                 cflags: str = "-O1", timeout: float = 30.0, env: dict | None = None):
        self.runtime, self.gc_dir, self.gc, self.cc, self.cflags = runtime, gc_dir, gc, cc, cflags
        self.timeout, self.env = timeout, env

    def run(self, ll_path: str, workdir: str) -> tuple[str, int]:
        exe = os.path.join(workdir, "a.out")
        cmd = [self.cc, *self.cflags.split(), "-DRT_PLAIN_MALLOC", "-Wno-override-module", "-I", self.gc_dir,
               ll_path, self.runtime, os.path.join(self.gc_dir, self.gc, "gc.c"), "-o", exe]
        r = subprocess.run(cmd, capture_output=True, text=True)
        if r.returncode != 0:
            raise InvalidMutant(r.stderr[-500:])
        p = subprocess.run([exe], capture_output=True, text=True, errors="replace", timeout=self.timeout,
                           env={**os.environ, **(self.env or {})})
        return p.stdout, p.returncode


class InvalidMutant(Exception):
    pass


def run_mutant(ir: str, m: Mutant, harness, expected: tuple[str, int]) -> Result:
    with tempfile.TemporaryDirectory(prefix="pitx-") as wd:
        path = os.path.join(wd, "m.ll")
        try:
            mutated = apply(ir, m)
        except ValueError as e:
            return Result(m, INVALID, str(e))
        with open(path, "w") as f:
            f.write(mutated)
        ok, err = verify(path)
        if not ok:
            return Result(m, INVALID, err.splitlines()[0] if err else "verifier failed")
        try:
            got = harness.run(path, wd)
        except subprocess.TimeoutExpired:
            return Result(m, TIMEOUT)
        except InvalidMutant as e:
            return Result(m, INVALID, str(e))
        if got != expected:
            return Result(m, CAUGHT, f"exit {got[1]} (expected {expected[1]})" if got[1] != expected[1] else "output differs")
        return Result(m, SURVIVED)


class InvalidInput(Exception):
    pass


def analyze(ll_path: str, harness, ops=None, skip_functions=None, jobs: int = 1, only_reachable=False) -> dict:
    """Verify the input, record the baseline, run every mutant. Raises InvalidInput for bad IR."""
    ok, err = verify(ll_path)
    if not ok:
        raise InvalidInput(err)
    ir = _read(ll_path)
    with tempfile.TemporaryDirectory(prefix="pitx-base-") as wd:
        expected = harness.run(ll_path, wd)
    mutants = enumerate_sites(ir, ops, re.compile(skip_functions) if skip_functions else None,
                              only_reachable)
    with ThreadPoolExecutor(max_workers=max(1, jobs)) as ex:
        results = list(ex.map(lambda m: run_mutant(ir, m, harness, expected), mutants))
    counts = {s: sum(r.status == s for r in results) for s in (CAUGHT, SURVIVED, TIMEOUT, INVALID)}
    scored = counts[CAUGHT] + counts[SURVIVED] + counts[TIMEOUT]
    score = (counts[CAUGHT] + counts[TIMEOUT]) / scored if scored else None
    return {"input": ll_path, "baseline": {"stdout": expected[0], "exit": expected[1]}, "counts": counts,
            "score": score, "mutants": [{**dataclasses.asdict(r.mutant), "status": r.status, "detail": r.detail}
                                        for r in results]}


def text_report(rep: dict) -> str:
    c = rep["counts"]
    s = f"{rep['score']:.0%}" if rep["score"] is not None else "n/a"
    lines = [f"pitx {rep['input']}: {len(rep['mutants'])} mutants, caught {c['caught']}, survived {c['survived']}, "
             f"timeout {c['timeout']}, invalid {c['invalid']}, score {s}"]
    for m in rep["mutants"]:
        if m["status"] != CAUGHT:
            lines.append(f"  {m['status'].upper():8} #{m['id']} {m['op']} @{m['function']}:{m['line']} "
                         f"'{m['original']}' -> '{m['replacement']}' {m['loc']} {m['detail']}".rstrip())
    return "\n".join(lines)


def main(argv=None):
    ap = argparse.ArgumentParser(description="pitx: LLVM IR mutation testing")
    ap.add_argument("ll", help="input .ll (a copy is mutated; the file is never modified)")
    ap.add_argument("--harness", choices=["lli", "link"], default="lli")
    ap.add_argument("--runtime", help="runtime.c (link harness)")
    ap.add_argument("--gc-dir", help="compiler/gc (link harness)")
    ap.add_argument("--gc", default="default")
    ap.add_argument("--ops", default=",".join(OPERATORS))
    ap.add_argument("--skip-functions", help="regex of function names to skip")
    ap.add_argument("--all-functions", action="store_true", help="also mutate functions unreachable from main")
    ap.add_argument("--timeout", type=float, default=10.0)
    ap.add_argument("--jobs", type=int, default=1)
    ap.add_argument("--json", help="write JSON report here")
    a = ap.parse_args(argv)
    h = (LliHarness(a.timeout) if a.harness == "lli"
         else LinkHarness(a.runtime, a.gc_dir, a.gc, timeout=a.timeout))
    try:
        rep = analyze(a.ll, h, a.ops.split(","), a.skip_functions, a.jobs, not a.all_functions)
    except InvalidInput as e:
        print(f"pitx: invalid input IR: {e}", file=sys.stderr); return 2
    print(text_report(rep))
    if a.json:
        with open(a.json, "w") as f:
            json.dump(rep, f, indent=2)
    return 0


if __name__ == "__main__":
    sys.exit(main())
