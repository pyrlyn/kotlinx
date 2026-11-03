#!/usr/bin/env python3
"""kxbindgen (prototype): one C header -> a Kotlin file of `external fun` declarations for KotlinX FFI.

Usage: kxbindgen.py <header.h> -o <out.kt> [--link <arg>]... [--cc clang] [-- <extra clang args>]

The header is parsed by clang itself (`-Xclang -ast-dump=json -fsyntax-only`), so there is no hand-written C
parser and no new dependency. Only declarations from <header.h> are bound (not from its #includes).
Emitted: functions with C-scalar/pointer signatures, enum constants and integer #define macros as `const val`.
Unsupported declarations (struct by value, varargs, function pointers, ...) are listed as comments.
The link arguments are written to the file header (`// kx-link:`) for `newkotlinc --link`.
"""
import json, os, re, subprocess, sys

SCALARS = {
    "int": "Int", "signed int": "Int", "int32_t": "Int", "long long": "Long", "int64_t": "Long", "long": "Long",
    "unsigned int": "UInt", "uint32_t": "UInt", "unsigned long long": "ULong", "uint64_t": "ULong", "unsigned long": "ULong",
    "size_t": "ULong", "ssize_t": "Long", "intptr_t": "Long", "uintptr_t": "ULong",
    "signed char": "Byte", "int8_t": "Byte", "char": "Byte",
    "_Bool": "Boolean", "bool": "Boolean", "float": "Float", "double": "Double", "void": "Unit",
}
KEYWORDS = {"as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is",
            "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias", "val", "var", "when", "while"}

def kt_type(qual, desugared):
    for t in (qual, desugared):
        if t is None: continue
        t = re.sub(r"\b(const|volatile|restrict|__restrict)\b", "", t).strip()
        t = re.sub(r"\s+", " ", t)
        if t.endswith("*") and "(" not in t: return "CPointer"
        if t in SCALARS: return SCALARS[t]
    return None

def ident(n): return f"`{n}`" if n in KEYWORDS else n

def main():
    a = sys.argv[1:]
    extra = a[a.index("--") + 1:] if "--" in a else []; a = a[:a.index("--")] if "--" in a else a
    header, out, links, cc = None, None, [], os.environ.get("CC", "clang")
    i = 0
    while i < len(a):
        if a[i] == "-o": out = a[i + 1]; i += 1
        elif a[i] == "--link": links.append(a[i + 1]); i += 1
        elif a[i] == "--cc": cc = a[i + 1]; i += 1
        else: header = a[i]
        i += 1
    if not header or not out: sys.exit(__doc__)
    hpath = os.path.realpath(header)
    ast = json.loads(subprocess.run([cc, "-x", "c", "-Xclang", "-ast-dump=json", "-fsyntax-only", *extra, hpath],
                                    check=True, capture_output=True, text=True).stdout)
    lines, skipped, cur, enum_vals = [], [], None, {}
    for d in ast.get("inner", []):
        loc = d.get("loc", {})
        f = loc.get("file") or loc.get("spellingLoc", {}).get("file")
        if f: cur = f                         # clang omits "file" while it is unchanged
        if loc.get("includedFrom") or (cur and os.path.realpath(cur) != hpath) or d.get("isImplicit"): continue
        kind, name = d.get("kind"), d.get("name")
        if kind == "FunctionDecl":
            ty = d["type"]["qualType"]
            if "..." in ty: skipped.append(f"{name}: variadic"); continue
            ps = [p for p in d.get("inner", []) if p.get("kind") == "ParmVarDecl"]
            ret_q = ty.split("(")[0].strip()
            ret = kt_type(ret_q, None)
            args, bad = [], None
            for k, p in enumerate(ps):
                t = kt_type(p["type"]["qualType"], p["type"].get("desugaredQualType"))
                if t is None or t == "Unit": bad = f"parameter {p.get('name', k)}: {p['type']['qualType']}"; break
                args.append(f"{ident(p.get('name') or f'p{k}')}: {t}")
            if ret is None: bad = bad or f"return type {ret_q}"
            if bad: skipped.append(f"{name}: unsupported {bad}"); continue
            lines.append(f"external fun {name}({', '.join(args)}): {ret}")
        elif kind == "EnumDecl":
            nxt = 0
            for e in d.get("inner", []):
                if e.get("kind") != "EnumConstantDecl": continue
                v = find_value(e)
                nxt = nxt if v is None else v
                enum_vals[e["name"]] = nxt; lines.append(("ENUM", e["name"])); nxt += 1
        elif kind in ("RecordDecl", "TypedefDecl") and kind == "RecordDecl" and d.get("completeDefinition"):
            skipped.append(f"struct {name or '<anon>'}: structs are opaque (pass as CPointer)")
    # Enum values and integer macros come from clang too: the preprocessor (-dM) and a constant probe.
    macros = []
    src = open(hpath).read()
    for m in re.finditer(r"^\s*#\s*define\s+([A-Za-z_]\w*)\s+(.+?)\s*$", src, re.M):
        if re.fullmatch(r"\(?-?(0x[0-9a-fA-F]+|\d+)[uUlL]*\)?", m.group(2)): macros.append(m.group(1))
    values = dict(enum_vals)
    if macros:
        probe = "".join(f'long long __kx_{n} = (long long)({n});\n' for n in macros)
        r = subprocess.run([cc, "-x", "c", "-include", hpath, *extra, "-Xclang", "-ast-dump=json", "-fsyntax-only", "-"],
                           input=probe, capture_output=True, text=True, check=True)
        for d in json.loads(r.stdout).get("inner", []):
            if d.get("kind") == "VarDecl" and d.get("name", "").startswith("__kx_"):
                v = find_value(d)
                if v is not None: values[d["name"][5:]] = v
    body = []
    for l in lines:
        if isinstance(l, tuple): body.append(const(l[1], values))
        else: body.append(l)
    body += [const(n, values) for n in macros]
    hdr = [f"// Generated by compiler/tools/kxbindgen.py from {os.path.basename(hpath)}. Do not edit.",
           f"// kx-link: {' '.join(links)}" if links else "// kx-link:"]
    hdr += [f"// skipped: {s}" for s in skipped]
    open(out, "w").write("\n".join(hdr + [b for b in body if b]) + "\n")
    print(f"{out}: {sum(1 for b in body if b and b.startswith('external'))} functions, "
          f"{sum(1 for b in body if b and b.startswith('const'))} constants, {len(skipped)} skipped")

def find_value(n):
    if "value" in n and n.get("kind") in ("IntegerLiteral", "ConstantExpr"): return int(n["value"])
    for c in n.get("inner", []):
        v = find_value(c)
        if v is not None: return v
    return None

def const(n, values):
    v = values.get(n)
    if v is None: return None
    return f"const val {n}: Int = {v}" if -2**31 <= v < 2**31 else f"const val {n}: Long = {v}L"

if __name__ == "__main__": main()
