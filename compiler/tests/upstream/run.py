#!/usr/bin/env python3
"""Run upstream Kotlin tests (compiler/tests/upstream/{box,native}) on Kotlin-X compiler.

Modes:
  box     file defines `fun box(): String`; a driver `fun main() { println(box()) }` is appended,
          pass = program prints exactly "OK" and exits 0.
  golden  native test with `main` and a sibling `<name>.out`; pass = stdout equals the .out file.

Usage: run.py [--nkc PATH] [--jobs N] [--timeout S] [--cflags F] [--json OUT] [paths...]
With no paths, runs every .kt under box/ and native/ (the tests that are kept because they pass).
Python stdlib only.
"""
import argparse, concurrent.futures as cf, json, os, re, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
DEFAULT_NKC = os.path.join(REPO, "compiler", "build", "newkotlinc.kexe")
RUNTIME = os.path.join(REPO, "compiler", "runtime", "runtime.c")
TMP = os.path.join(REPO, "compiler", "build", "upstream-tmp")
DRIVER = "\nfun main() { println(box()) }\n"

# Directives that make a test not applicable to Kotlin-X compiler (a native, no-reflection, no-coroutines target).
NA_PATTERNS = [
    (r"^//\s*TARGET_BACKEND:\s*(?!.*\bNATIVE\b)", "TARGET_BACKEND non-native"),
    (r"^//\s*(IGNORE_BACKEND|IGNORE_BACKEND_K2|IGNORE_NATIVE|DONT_TARGET_EXACT_BACKEND)\S*:.*\b(NATIVE|ANY)\b", "IGNORE_BACKEND NATIVE/ANY"),
    (r"^//\s*IGNORE_NATIVE", "IGNORE_NATIVE"),
    (r"^//\s*WITH_REFLECT", "WITH_REFLECT"),
    (r"^//\s*WITH_COROUTINES", "WITH_COROUTINES"),
    (r"^//\s*FULL_JDK", "FULL_JDK"),
    (r"^//\s*JVM_TARGET", "JVM_TARGET"),
    (r"^//\s*(FILE|MODULE):", "multi-file/module test"),
    (r"^//\s*(EXPECT_ACTUAL_LINKER|MULTIPLATFORM)", "multiplatform"),
    (r"^\s*import\s+(java|javax|kotlin\.jvm|kotlin\.reflect|kotlinx\.cinterop|kotlin\.native\.concurrent|platform)\.", "platform/reflect import"),
    (r"^\s*@file:Jvm", "JVM file annotation"),
    (r"^//\s*(EXPECTED_OUTPUT|OUTPUT_DATA_FILE|INPUT_DATA_FILE|FREE_COMPILER_ARGS|KIND|PROGRAM_ARGS|ENTRY_POINT)", "native test-runner directive"),
    (r"^//\s*DISABLE_NATIVE", "DISABLE_NATIVE"),
]
NA_RE = [(re.compile(p, re.M), why) for p, why in NA_PATTERNS]

# Compiler error message -> feature bucket (first match wins). Messages are normalised first.
FEATURE_RULES = [
    (r"\w*(Exception|Error)'\)?$|AssertionError", "exceptions"),
    (r"top-level token \(at '(val|var)'\)", "top-level properties"),
    (r"at '(step|downTo|until)'|ranges are only", "ranges (step/downTo/until, ranges as values)"),
    (r"class bodies are not supported", "class bodies / constructors"),
    (r"unknown (function|name) '(assert\w*|fail)'", "stdlib: kotlin.test asserts"),
    (r"unknown name '(break|continue)'", "break / continue"),
    (r"needs an explicit return type", "type inference (expression bodies)"),
    (r"expected identifier \(at '\('\)", "lambdas / function types / references"),
    (r"expected '\(' \(at '\.'\)", "extension functions"),
    (r"top-level token \(at ':'\)", "inheritance (open/abstract/sealed)"),
    (r"expected identifier \(at '='\)", "default arguments"),
    (r"expected identifier \(at '\['\)", "destructuring declarations"),
    (r"unknown escape", "strings / templates"),
    (r"unknown type '?(U\w+|Any\??|Double|Float|Long|Byte|Short|Char\w*|\w*Array)'?", "types: {0}"),
    (r"at '<'|at '>'|type parameter|generic", "generics"),
    (r"at '->'|lambda|function type|at '::'", "lambdas / function types / references"),
    (r"at 'is'|at 'as'|at '!is'|at 'as\?'", "type checks / casts (is, as)"),
    (r"at 'try'|at 'throw'|at 'catch'|exception", "exceptions"),
    (r"at '\?'|at '\?\.'|at '\?:'|at '!!'|nullable|null", "nullable types"),
    (r"at 'object'|companion", "objects / companion objects"),
    (r"at 'enum'|enum", "enum classes"),
    (r"at 'interface'|interface", "interfaces"),
    (r"at 'open'|at 'abstract'|at 'override'|at 'sealed'|super|inherit", "inheritance (open/abstract/sealed)"),
    (r"at 'data'|data class", "data classes"),
    (r"at 'inline'|at 'value'|@JvmInline|inline class|value class", "inline/value classes"),
    (r"at 'package'|at 'import'", "package/import declarations"),
    (r"at '@'|annotation", "annotations"),
    (r"at 'when'|when", "when expressions"),
    (r"at 'operator'|at 'infix'|operator", "operator/infix functions"),
    (r"at 'vararg'|vararg", "varargs"),
    (r"at 'private'|at 'internal'|at 'protected'|at 'public'|at 'const'|at 'lateinit'|at 'tailrec'|modifier", "modifiers (private/const/lateinit/...)"),
    (r"at 'get'|at 'set'|at 'by'|getter|setter|delegat", "property accessors / delegation"),
    (r"at 'typealias'", "typealias"),
    (r"at '\$'|template|string", "strings / templates"),
    (r"at 'do'|at 'break'|at 'continue'|label|at 'this@'|@'", "loops / labels"),
    (r"at '\{'|at 'class'|class body|constructor|at 'init'", "class bodies / constructors"),
    (r"at 'fun'|local fun|extension", "local / extension functions"),
    (r"unknown method '([^']+)'|no method '([^']+)'", "stdlib API: {0}"),
    (r"unknown (name|function|type|class) '([^']+)'", "unknown {1} (stdlib API / unsupported decl)"),
    (r"type mismatch|expected type|cannot assign|incompatible", "type inference / mismatch"),
    (r"(at '[^']*')", "other syntax ({0})"),
]

def na_reason(src):
    for rx, why in NA_RE:
        if rx.search(src):
            return why
    return None

def mode_for(path, src):
    if re.search(r"^\s*fun\s+box\s*\(\s*\)", src, re.M):
        return "box", None
    out = os.path.splitext(path)[0] + ".out"
    if re.search(r"^\s*fun\s+main\s*\(", src, re.M) and os.path.exists(out):
        return "golden", open(out, encoding="utf-8", errors="replace").read()
    return None, None

def normalise(msg):
    m = msg.strip().splitlines()[0] if msg.strip() else ""
    m = re.sub(r"^\S+\.kt:\s*", "", m)
    m = re.sub(r"error:\s*line \d+:\s*", "", m)
    return m

def feature_bucket(msg):
    m = normalise(msg)
    for rx, name in FEATURE_RULES:
        mm = re.search(rx, m, re.I)
        if mm:
            if "{" in name:
                groups = [g for g in mm.groups() if g] or [""]
                try:
                    return name.format(*groups, *groups)
                except IndexError:
                    return name
            return name
    return "other: " + m[:60]

def run_one(path, nkc, timeout, cflags, src=None):
    """Return dict(status, detail, feature).
    status in PASS N/A SKIPPED_TARGET UNSUPPORTED MISCOMPILE COMPILER_CRASH. Test files are only read; the
    box() driver and the OPTIONAL_JVM_INLINE_ANNOTATION macro are applied to a temporary copy, as upstream does."""
    if src is None:
        src = open(path, encoding="utf-8", errors="replace").read()
    tb = re.findall(r"^//\s*TARGET_BACKEND:\s*(.+)$", src, re.M)
    targets = sorted({b.strip() for m in tb for b in m.split(",") if b.strip()})
    if targets and "NATIVE" not in targets:
        # Applies to other backends (JS/Wasm); only the native target exists for now.
        return {"status": "SKIPPED_TARGET", "detail": "targets: " + ", ".join(targets)}
    why = na_reason(src)
    if why:
        return {"status": "N/A", "detail": why}
    # Test-infrastructure macro: the upstream harness expands it to @JvmInline on JVM and to nothing on Native.
    src = src.replace("OPTIONAL_JVM_INLINE_ANNOTATION", "")
    mode, expected = mode_for(path, src)
    if mode is None:
        return {"status": "N/A", "detail": "no oracle (no box() and no .out)"}
    os.makedirs(TMP, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="nkup", dir=TMP) as td:
        kt = os.path.join(td, "t.kt"); exe = os.path.join(td, "t")
        with open(kt, "w", encoding="utf-8") as f:
            f.write(src + (DRIVER if mode == "box" and not re.search(r"^\s*fun\s+main\s*\(", src, re.M) else ""))
        cmd = [nkc, kt, "-o", exe, "--runtime", RUNTIME]
        if cflags:
            cmd += ["--cflags", cflags]
        try:
            c = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, errors="replace")
        except subprocess.TimeoutExpired:
            return {"status": "COMPILER_CRASH", "detail": "compile timeout"}
        err = (c.stderr + c.stdout).strip()
        if c.returncode != 0 or not os.path.exists(exe):
            if "clang failed" in err or ".ll:" in err or c.returncode < 0 or "kfun:" in err or "Uncaught Kotlin exception" in err:
                return {"status": "COMPILER_CRASH", "detail": err[:400]}
            return {"status": "UNSUPPORTED", "detail": normalise(err)[:300], "feature": feature_bucket(err)}
        try:
            r = subprocess.run([exe], capture_output=True, text=True, timeout=timeout, errors="replace", cwd=td)
        except subprocess.TimeoutExpired:
            return {"status": "MISCOMPILE", "detail": "run timeout"}
        out = r.stdout
        ok = (out.strip() == "OK") if mode == "box" else (out.rstrip("\n") == expected.rstrip("\n"))
        if ok and r.returncode == 0:
            return {"status": "PASS", "detail": mode}
        return {"status": "MISCOMPILE", "detail": f"exit={r.returncode} stdout={out[-200:]!r} stderr={r.stderr[-200:]!r}"}

def run_many(items, nkc, jobs, timeout, cflags, progress=True):
    """items: list of (key, path, src_or_None). Returns {key: result}."""
    res = {}
    with cf.ThreadPoolExecutor(jobs) as ex:
        futs = {ex.submit(run_one, p, nkc, timeout, cflags, s): k for k, p, s in items}
        for i, f in enumerate(cf.as_completed(futs), 1):
            k = futs[f]
            try:
                res[k] = f.result()
            except Exception as e:  # runner bug, not a test result
                res[k] = {"status": "COMPILER_CRASH", "detail": f"runner error: {e}"}
            if progress and i % 200 == 0:
                print(f"  {i}/{len(items)}", file=sys.stderr, flush=True)
    return res

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--nkc", default=os.environ.get("NKC", DEFAULT_NKC))
    ap.add_argument("--jobs", type=int, default=6)
    ap.add_argument("--timeout", type=float, default=10)
    ap.add_argument("--cflags", default=None)
    ap.add_argument("--json")
    ap.add_argument("paths", nargs="*")
    a = ap.parse_args()
    paths = a.paths or sorted(os.path.join(dp, f) for sub in ("box", "native")
                              for dp, _, fs in os.walk(os.path.join(HERE, sub)) for f in fs if f.endswith(".kt"))
    res = run_many([(p, p, None) for p in paths], a.nkc, a.jobs, a.timeout, a.cflags)
    counts = {}
    for p in sorted(res):
        s = res[p]["status"]; counts[s] = counts.get(s, 0) + 1
        if s != "PASS":
            print(f"{s} {os.path.relpath(p, HERE)}: {res[p]['detail']}")
    print("summary:", " ".join(f"{k}={v}" for k, v in sorted(counts.items())), f"total={len(res)}")
    if a.json:
        json.dump({os.path.relpath(k, HERE): v for k, v in res.items()}, open(a.json, "w"), indent=1)
    sys.exit(0 if counts.get("PASS", 0) == len(res) else 1)

if __name__ == "__main__":
    main()
