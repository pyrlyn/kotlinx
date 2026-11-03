//! pitx: mutation testing on LLVM IR emitted by the KotlinX compiler (PIT-style, IR instead of JVM bytecode).
//!
//! Library + CLI (src/main.zig). Works only on copies of IR text; never touches sources. Zig std only.
//! See DESIGN.md.
const std = @import("std");
const Io = std.Io;
const Allocator = std.mem.Allocator;
const mem = std.mem;

// ---------------------------------------------------------------- operators

/// One textual mutation site inside a line (offsets into the comment-stripped code).
pub const Site = struct { start: usize, end: usize, original: []const u8, replacement: []const u8 };

/// An operator finds at most one site per line (the first match, like `re.search`).
pub const Operator = struct {
    name: []const u8,
    find: *const fn (code: []const u8) ?Site,
};

const icmp_preds = [_][2][]const u8{
    .{ "eq", "ne" },   .{ "ne", "eq" },   .{ "slt", "sge" }, .{ "sge", "slt" }, .{ "sgt", "sle" },
    .{ "sle", "sgt" }, .{ "ult", "uge" }, .{ "uge", "ult" }, .{ "ugt", "ule" }, .{ "ule", "ugt" },
};

/// Negated icmp predicate, or null if `p` is not an icmp predicate.
pub fn icmpFlip(p: []const u8) ?[]const u8 {
    for (icmp_preds) |e| if (mem.eql(u8, e[0], p)) return e[1];
    return null;
}

pub const icmp_predicates = blk: {
    var out: [icmp_preds.len][]const u8 = undefined;
    for (icmp_preds, 0..) |e, i| out[i] = e[0];
    break :blk out;
};

fn isWord(c: u8) bool {
    return std.ascii.isAlphanumeric(c) or c == '_';
}
fn isSpace(c: u8) bool {
    return c == ' ' or c == '\t' or c == '\r' or c == '\n' or c == 0x0b or c == 0x0c;
}
fn skipSpace(s: []const u8, i: usize) usize {
    var j = i;
    while (j < s.len and isSpace(s[j])) j += 1;
    return j;
}
fn wordEnd(s: []const u8, i: usize) usize {
    var j = i;
    while (j < s.len and isWord(s[j])) j += 1;
    return j;
}

/// `= icmp <pred>`: flip the predicate.
pub fn findIcmp(code: []const u8) ?Site {
    for (code, 0..) |c, i| {
        if (c != '=') continue;
        const j = skipSpace(code, i + 1);
        if (!mem.startsWith(u8, code[j..], "icmp")) continue;
        const k = j + 4;
        const p = skipSpace(code, k);
        if (p == k) continue; // needs at least one space
        const e = wordEnd(code, p);
        if (icmpFlip(code[p..e])) |q| return .{ .start = p, .end = e, .original = code[p..e], .replacement = q };
    }
    return null;
}

/// `= add|sub [nuw] [nsw]`: integer add<->sub. Wrap flags are dropped (poison for the swapped op).
/// fadd/fsub never match: the word after `=` must be exactly add or sub.
pub fn findArith(code: []const u8) ?Site {
    for (code, 0..) |c, i| {
        if (c != '=') continue;
        const s = skipSpace(code, i + 1);
        const w = wordEnd(code, s);
        const word = code[s..w];
        const repl: []const u8 = if (mem.eql(u8, word, "add")) "sub" else if (mem.eql(u8, word, "sub")) "add" else continue;
        var end = w;
        while (true) {
            const f = skipSpace(code, end);
            if (f == end) break;
            const fe = wordEnd(code, f);
            const flag = code[f..fe];
            if (!mem.eql(u8, flag, "nuw") and !mem.eql(u8, flag, "nsw")) break;
            end = fe;
        }
        return .{ .start = s, .end = end, .original = code[s..end], .replacement = repl };
    }
    return null;
}

pub const icmp_flip: Operator = .{ .name = "icmp-flip", .find = findIcmp };
pub const arith_swap: Operator = .{ .name = "arith-swap", .find = findArith };
pub const all_operators = [_]Operator{ icmp_flip, arith_swap };

pub fn operatorByName(name: []const u8) ?Operator {
    for (all_operators) |o| if (mem.eql(u8, o.name, name)) return o;
    return null;
}

// ---------------------------------------------------------------- IR scanning

pub const Mutant = struct {
    id: usize,
    op: Operator,
    line: usize, // 1-based line in the IR
    function: []const u8,
    original: []const u8,
    replacement: []const u8,
    loc: []const u8 = "", // Kotlin source location via !dbg, if any
};

fn stripComment(line: []const u8) []const u8 {
    return line[0 .. mem.indexOfScalar(u8, line, ';') orelse line.len];
}

fn isNameChar(c: u8, quote: bool) bool {
    return isWord(c) or c == '.' or c == '$' or c == '-' or (quote and c == '"');
}

/// `define ... @name(`: the defined function's name.
pub fn defineName(line: []const u8) ?[]const u8 {
    if (!mem.startsWith(u8, line, "define")) return null;
    if (line.len > 6 and isWord(line[6])) return null;
    var i: usize = 6;
    while (i < line.len) : (i += 1) {
        if (line[i] != '@') continue;
        var j = i + 1;
        while (j < line.len and isNameChar(line[j], true)) j += 1;
        if (j > i + 1 and j < line.len and line[j] == '(') return line[i + 1 .. j];
    }
    return null;
}

/// `@name` / `@"quoted name"` at `i` (line[i] == '@'): returns name and end offset.
fn refAt(s: []const u8, i: usize) ?struct { name: []const u8, end: usize } {
    if (i + 1 >= s.len) return null;
    if (isNameChar(s[i + 1], false)) {
        var j = i + 1;
        while (j < s.len and isNameChar(s[j], false)) j += 1;
        return .{ .name = s[i + 1 .. j], .end = j };
    }
    if (s[i + 1] == '"') {
        const close = mem.indexOfScalarPos(u8, s, i + 2, '"') orelse return null;
        if (close == i + 2) return null;
        return .{ .name = s[i + 1 .. close + 1], .end = close + 1 };
    }
    return null;
}

fn addRefs(gpa: Allocator, set: *std.StringHashMapUnmanaged(void), s: []const u8) !void {
    var i: usize = 0;
    while (i < s.len) {
        if (s[i] == '@') if (refAt(s, i)) |r| {
            try set.put(gpa, r.name, {});
            i = r.end;
            continue;
        };
        i += 1;
    }
}

pub const NameSet = std.StringHashMapUnmanaged(void);

/// Functions transitively referenced from @main (calls, function pointers, and globals such as vtables
/// referenced by reachable code). Conservative: any textual @ref counts as an edge. `a` should be an arena.
pub fn reachableFunctions(a: Allocator, ir: []const u8) !NameSet {
    var refs: std.StringHashMapUnmanaged(NameSet) = .empty;
    var cur: ?[]const u8 = null;
    var it = mem.splitScalar(u8, ir, '\n');
    while (it.next()) |line| {
        const code = stripComment(line);
        if (defineName(code)) |d| {
            cur = d;
            const g = try refs.getOrPut(a, d);
            if (!g.found_existing) g.value_ptr.* = .empty;
            continue;
        }
        if (cur) |c| {
            if (mem.startsWith(u8, line, "}")) cur = null else try addRefs(a, refs.getPtr(c).?, code);
            continue;
        }
        if (code.len > 0 and code[0] == '@') if (refAt(code, 0)) |r| {
            const eq = skipSpace(code, r.end);
            if (eq < code.len and code[eq] == '=') {
                const g = try refs.getOrPut(a, r.name);
                if (!g.found_existing) g.value_ptr.* = .empty;
                try addRefs(a, g.value_ptr, code[eq + 1 ..]);
            }
        };
    }
    var seen: NameSet = .empty;
    var todo: std.ArrayList([]const u8) = .empty;
    if (refs.contains("main")) try todo.append(a, "main");
    while (todo.pop()) |n| {
        if (seen.contains(n)) continue;
        try seen.put(a, n, {});
        if (refs.get(n)) |rs| {
            var ki = rs.keyIterator();
            while (ki.next()) |k| if (!seen.contains(k.*)) try todo.append(a, k.*);
        }
    }
    return seen;
}

/// Function-name filter for --skip-functions: comma-separated patterns, `^x` prefix, `x$` suffix,
/// `^x$` exact, otherwise substring.
pub const SkipFilter = struct {
    patterns: []const u8,
    pub fn matches(f: SkipFilter, name: []const u8) bool {
        var it = mem.splitScalar(u8, f.patterns, ',');
        while (it.next()) |raw| {
            if (raw.len == 0) continue;
            var p = raw;
            const pre = p[0] == '^';
            if (pre) p = p[1..];
            const suf = p.len > 0 and p[p.len - 1] == '$';
            if (suf) p = p[0 .. p.len - 1];
            const hit = if (pre and suf) mem.eql(u8, name, p) else if (pre) mem.startsWith(u8, name, p) else if (suf) mem.endsWith(u8, name, p) else mem.indexOf(u8, name, p) != null;
            if (hit) return true;
        }
        return false;
    }
};

pub const EnumOptions = struct {
    ops: []const Operator = &all_operators,
    skip: ?SkipFilter = null,
    /// Skip functions not reachable from @main (e.g. the unused stdlib prelude of every KotlinX module).
    only_reachable: bool = false,
};

fn dbgRef(line: []const u8) ?[]const u8 {
    var from: usize = 0;
    while (mem.indexOfPos(u8, line, from, "!dbg")) |i| {
        from = i + 1;
        const j = skipSpace(line, i + 4);
        if (j == i + 4 or j >= line.len or line[j] != '!') continue;
        var k = j + 1;
        while (k < line.len and std.ascii.isDigit(line[k])) k += 1;
        if (k > j + 1) return line[j..k];
    }
    return null;
}

/// `!N = ... DILocation(line: L` -> (N, L)
fn diLocation(line: []const u8) ?struct { id: []const u8, line: []const u8 } {
    if (line.len < 2 or line[0] != '!') return null;
    var k: usize = 1;
    while (k < line.len and std.ascii.isDigit(line[k])) k += 1;
    if (k == 1) return null;
    const eq = skipSpace(line, k);
    if (eq >= line.len or line[eq] != '=') return null;
    const key = "DILocation(line:";
    const d = mem.lastIndexOf(u8, line[eq..], key) orelse return null;
    const s = skipSpace(line, eq + d + key.len);
    var e = s;
    while (e < line.len and std.ascii.isDigit(line[e])) e += 1;
    if (e == s) return null;
    return .{ .id = line[0..k], .line = line[s..e] };
}

/// All mutation sites in function bodies, in IR order. Slices point into `ir`; `a` should be an arena.
pub fn enumerateSites(a: Allocator, ir: []const u8, opts: EnumOptions) ![]Mutant {
    var reach: ?NameSet = if (opts.only_reachable) try reachableFunctions(a, ir) else null;
    _ = &reach;
    var diloc: std.StringHashMapUnmanaged([]const u8) = .empty;
    var it = mem.splitScalar(u8, ir, '\n');
    while (it.next()) |l| if (diLocation(l)) |d| try diloc.put(a, d.id, d.line);
    var out: std.ArrayList(Mutant) = .empty;
    var fn_name: ?[]const u8 = null;
    var n: usize = 0;
    it = mem.splitScalar(u8, ir, '\n');
    while (it.next()) |line| : (n += 1) {
        if (defineName(line)) |d| {
            fn_name = d;
            continue;
        }
        if (mem.startsWith(u8, line, "}")) {
            fn_name = null;
            continue;
        }
        const f = fn_name orelse continue;
        if (opts.skip) |s| if (s.matches(f)) continue;
        if (reach) |r| if (!r.contains(f)) continue;
        const code = stripComment(line);
        for (opts.ops) |op| {
            const site = op.find(code) orelse continue;
            var loc: []const u8 = "";
            if (dbgRef(line)) |ref| if (diloc.get(ref)) |ln| {
                loc = try std.fmt.allocPrint(a, "line {s}", .{ln});
            };
            try out.append(a, .{ .id = out.items.len, .op = op, .line = n + 1, .function = f, .original = site.original, .replacement = site.replacement, .loc = loc });
        }
    }
    return out.toOwnedSlice(a);
}

/// A copy of `ir` with mutant `m` applied (exactly one textual site).
pub fn apply(a: Allocator, ir: []const u8, m: Mutant) ![]u8 {
    var line_start: usize = 0;
    var n: usize = 1;
    while (n < m.line) : (n += 1) {
        line_start = (mem.indexOfScalarPos(u8, ir, line_start, '\n') orelse return error.SiteNotFound) + 1;
    }
    const line_end = mem.indexOfScalarPos(u8, ir, line_start, '\n') orelse ir.len;
    const site = m.op.find(stripComment(ir[line_start..line_end])) orelse return error.SiteNotFound;
    if (!mem.eql(u8, site.original, m.original)) return error.SiteNotFound;
    return mem.concat(a, u8, &.{ ir[0 .. line_start + site.start], m.replacement, ir[line_start + site.end ..] });
}

// ---------------------------------------------------------------- runner

pub const Status = enum { caught, survived, timeout, invalid };

pub const Result = struct { mutant: Mutant, status: Status, detail: []const u8 = "" };

/// Observable behaviour of one run: stdout and exit code (negative signal number if killed).
pub const Outcome = struct { stdout: []const u8, exit: i32 };

pub const RunOutcome = union(enum) { ok: Outcome, timeout, invalid: []const u8 };

pub const Ctx = struct {
    io: Io,
    llvm_as: []const u8,
    lli: []const u8,

    /// Tool lookup: $PITX_LLVM_AS / $PITX_LLI, else next to `mise which opt` (the repo's pinned LLVM),
    /// else PATH.
    pub fn init(a: Allocator, io: Io, env: ?*const std.process.Environ.Map) !Ctx {
        return .{ .io = io, .llvm_as = try findTool(a, io, env, "llvm-as", "PITX_LLVM_AS"), .lli = try findTool(a, io, env, "lli", "PITX_LLI") };
    }
};

fn findTool(a: Allocator, io: Io, env: ?*const std.process.Environ.Map, name: []const u8, key: []const u8) ![]const u8 {
    if (env) |e| if (e.get(key)) |v| if (v.len > 0) return a.dupe(u8, v);
    const r = std.process.run(a, io, .{ .argv = &.{ "mise", "which", "opt" } }) catch return name;
    const opt = mem.trim(u8, r.stdout, " \r\n");
    if (opt.len > 0 and r.term.success()) {
        const d1 = std.fs.path.dirname(opt) orelse "";
        const d2 = try std.fs.path.join(a, &.{ std.fs.path.dirname(d1) orelse "", "bin" });
        for ([_][]const u8{ d1, d2 }) |d| {
            const p = try std.fs.path.join(a, &.{ d, name });
            Io.Dir.cwd().access(io, p, .{ .execute = true }) catch continue;
            return p;
        }
    }
    return name;
}

fn exitCode(t: std.process.Child.Term) i32 {
    return switch (t) {
        .exited => |c| c,
        .signal => |s| -@as(i32, @intCast(@intFromEnum(s))),
        else => -1000,
    };
}

/// llvm-as parses and runs the verifier. Returns null if valid, else its stderr.
pub fn verify(a: Allocator, ctx: Ctx, path: []const u8) !?[]const u8 {
    const r = try std.process.run(a, ctx.io, .{ .argv = &.{ ctx.llvm_as, path, "-o", "/dev/null" } });
    if (r.term.success()) return null;
    const e = mem.trim(u8, r.stderr, " \r\n");
    return if (e.len > 0) e else "verifier failed";
}

fn runTimed(a: Allocator, io: Io, argv: []const []const u8, timeout_ms: i64) !RunOutcome {
    const r = std.process.run(a, io, .{ .argv = argv, .timeout = .{ .duration = .{ .raw = .fromMilliseconds(timeout_ms), .clock = .awake } } }) catch |e| switch (e) {
        error.Timeout => return .timeout,
        else => return e,
    };
    return .{ .ok = .{ .stdout = r.stdout, .exit = exitCode(r.term) } };
}

pub const Harness = union(enum) {
    /// Run a standalone IR module with `lli`.
    lli: struct { timeout_ms: i64 = 10_000 },
    /// Link a KotlinX-emitted module with the runtime + GC via clang (as the driver does) and run it.
    link: struct {
        runtime: []const u8,
        gc_dir: []const u8,
        gc: []const u8 = "default",
        cc: []const u8 = "/usr/bin/clang",
        cflags: []const u8 = "-O1",
        timeout_ms: i64 = 30_000,
    },

    pub fn run(h: Harness, a: Allocator, ctx: Ctx, ll: []const u8, workdir: []const u8) !RunOutcome {
        switch (h) {
            .lli => |l| return runTimed(a, ctx.io, &.{ ctx.lli, ll }, l.timeout_ms),
            .link => |l| {
                const exe = try std.fs.path.join(a, &.{ workdir, "a.out" });
                var argv: std.ArrayList([]const u8) = .empty;
                try argv.append(a, l.cc);
                var fl = mem.tokenizeScalar(u8, l.cflags, ' ');
                while (fl.next()) |f| try argv.append(a, f);
                const gc_c = try std.fs.path.join(a, &.{ l.gc_dir, l.gc, "gc.c" });
                try argv.appendSlice(a, &.{ "-DRT_PLAIN_MALLOC", "-Wno-override-module", "-I", l.gc_dir, ll, l.runtime, gc_c, "-o", exe });
                const r = try std.process.run(a, ctx.io, .{ .argv = argv.items });
                if (!r.term.success()) return .{ .invalid = r.stderr[r.stderr.len -| 500..] };
                return runTimed(a, ctx.io, &.{exe}, l.timeout_ms);
            },
        }
    }
};

fn makeTempDir(a: Allocator, io: Io, prefix: []const u8) ![]const u8 {
    var rnd: [8]u8 = undefined;
    io.random(&rnd);
    const base = std.c.getenv("TMPDIR");
    const root: []const u8 = if (base) |b| mem.span(b) else "/tmp";
    const p = try std.fmt.allocPrint(a, "{s}/{s}{x}", .{ mem.trimEnd(u8, root, "/"), prefix, rnd });
    try Io.Dir.cwd().createDirPath(io, p);
    return p;
}

pub fn runMutant(a: Allocator, ctx: Ctx, ir: []const u8, m: Mutant, h: Harness, expected: Outcome) !Result {
    const wd = try makeTempDir(a, ctx.io, "pitx-");
    defer Io.Dir.cwd().deleteTree(ctx.io, wd) catch {};
    const mutated = apply(a, ir, m) catch return .{ .mutant = m, .status = .invalid, .detail = "site not found" };
    const path = try std.fs.path.join(a, &.{ wd, "m.ll" });
    try Io.Dir.cwd().writeFile(ctx.io, .{ .sub_path = path, .data = mutated });
    if (try verify(a, ctx, path)) |err| {
        const first = err[0 .. mem.indexOfScalar(u8, err, '\n') orelse err.len];
        return .{ .mutant = m, .status = .invalid, .detail = first };
    }
    switch (try h.run(a, ctx, path, wd)) {
        .timeout => return .{ .mutant = m, .status = .timeout },
        .invalid => |e| return .{ .mutant = m, .status = .invalid, .detail = e },
        .ok => |got| {
            if (got.exit != expected.exit)
                return .{ .mutant = m, .status = .caught, .detail = try std.fmt.allocPrint(a, "exit {d} (expected {d})", .{ got.exit, expected.exit }) };
            if (!mem.eql(u8, got.stdout, expected.stdout)) return .{ .mutant = m, .status = .caught, .detail = "output differs" };
            return .{ .mutant = m, .status = .survived };
        },
    }
}

pub const Counts = struct { caught: usize = 0, survived: usize = 0, timeout: usize = 0, invalid: usize = 0 };

pub const Report = struct {
    input: []const u8,
    baseline: Outcome,
    counts: Counts,
    score: ?f64,
    results: []Result,
};

pub const AnalyzeOptions = struct {
    enum_opts: EnumOptions = .{},
    jobs: usize = 1,
};

pub const AnalyzeError = struct { invalid_input: []const u8 = "" };

/// Verify the input, record the baseline, run every mutant. Returns error.InvalidInput (details in
/// `diag.invalid_input`) for bad IR. `a` should be an arena; worker threads use their own arenas.
pub fn analyze(a: Allocator, ctx: Ctx, ll: []const u8, h: Harness, opts: AnalyzeOptions, diag: ?*AnalyzeError) !Report {
    if (try verify(a, ctx, ll)) |err| {
        if (diag) |d| d.invalid_input = err;
        return error.InvalidInput;
    }
    const ir = try Io.Dir.cwd().readFileAlloc(ctx.io, ll, a, .unlimited);
    const expected = blk: {
        const wd = try makeTempDir(a, ctx.io, "pitx-base-");
        defer Io.Dir.cwd().deleteTree(ctx.io, wd) catch {};
        break :blk switch (try h.run(a, ctx, ll, wd)) {
            .ok => |o| o,
            else => return error.BaselineFailed,
        };
    };
    const mutants = try enumerateSites(a, ir, opts.enum_opts);
    const results = try a.alloc(Result, mutants.len);
    if (opts.jobs <= 1 or mutants.len <= 1) {
        for (mutants, 0..) |m, i| results[i] = try runMutant(a, ctx, ir, m, h, expected);
    } else {
        var next = std.atomic.Value(usize).init(0);
        var failed = std.atomic.Value(bool).init(false);
        const W = struct {
            fn work(ctx_: Ctx, arena: *std.heap.ArenaAllocator, ir_: []const u8, ms: []const Mutant, rs: []Result, h_: Harness, exp: Outcome, nx: *std.atomic.Value(usize), fl: *std.atomic.Value(bool)) void {
                while (true) {
                    const i = nx.fetchAdd(1, .monotonic);
                    if (i >= ms.len) return;
                    rs[i] = runMutant(arena.allocator(), ctx_, ir_, ms[i], h_, exp) catch {
                        fl.store(true, .monotonic);
                        return;
                    };
                }
            }
        };
        const n = @min(opts.jobs, mutants.len);
        const threads = try a.alloc(std.Thread, n);
        const arenas = try a.alloc(std.heap.ArenaAllocator, n);
        for (arenas) |*ar| ar.* = .init(std.heap.page_allocator);
        defer for (arenas) |*ar| ar.deinit();
        for (threads, arenas) |*t, *ar| t.* = try std.Thread.spawn(.{}, W.work, .{ ctx, ar, ir, mutants, results, h, expected, &next, &failed });
        for (threads) |t| t.join();
        if (failed.load(.monotonic)) return error.WorkerFailed;
        for (results) |*r| r.detail = try a.dupe(u8, r.detail);
    }
    var c: Counts = .{};
    for (results) |r| switch (r.status) {
        .caught => c.caught += 1,
        .survived => c.survived += 1,
        .timeout => c.timeout += 1,
        .invalid => c.invalid += 1,
    };
    const scored = c.caught + c.survived + c.timeout;
    const score: ?f64 = if (scored == 0) null else @as(f64, @floatFromInt(c.caught + c.timeout)) / @as(f64, @floatFromInt(scored));
    return .{ .input = ll, .baseline = expected, .counts = c, .score = score, .results = results };
}

// ---------------------------------------------------------------- reports

pub fn textReport(a: Allocator, rep: Report) ![]u8 {
    var w: Io.Writer.Allocating = .init(a);
    const o = &w.writer;
    const c = rep.counts;
    try o.print("pitx {s}: {d} mutants, caught {d}, survived {d}, timeout {d}, invalid {d}, score ", .{ rep.input, rep.results.len, c.caught, c.survived, c.timeout, c.invalid });
    if (rep.score) |s| try o.print("{d}%", .{@as(i64, @intFromFloat(@round(s * 100)))}) else try o.writeAll("n/a");
    for (rep.results) |r| {
        if (r.status == .caught) continue;
        const m = r.mutant;
        var st: [8]u8 = @splat(' ');
        const name = @tagName(r.status);
        for (name, 0..) |ch, i| st[i] = std.ascii.toUpper(ch);
        var line: Io.Writer.Allocating = .init(a);
        try line.writer.print("  {s} #{d} {s} @{s}:{d} '{s}' -> '{s}' {s} {s}", .{ &st, m.id, m.op.name, m.function, m.line, m.original, m.replacement, m.loc, r.detail });
        try o.print("\n{s}", .{mem.trimEnd(u8, line.written(), " \t\r\n")});
    }
    return w.toOwnedSlice();
}

const JsonMutant = struct { id: usize, op: []const u8, line: usize, function: []const u8, original: []const u8, replacement: []const u8, loc: []const u8, status: Status, detail: []const u8 };
const JsonReport = struct {
    input: []const u8,
    baseline: struct { stdout: []const u8, exit: i32 },
    counts: Counts,
    score: ?f64,
    mutants: []JsonMutant,
};

pub fn jsonReport(a: Allocator, rep: Report) ![]u8 {
    const ms = try a.alloc(JsonMutant, rep.results.len);
    for (rep.results, ms) |r, *j| {
        const m = r.mutant;
        j.* = .{ .id = m.id, .op = m.op.name, .line = m.line, .function = m.function, .original = m.original, .replacement = m.replacement, .loc = m.loc, .status = r.status, .detail = r.detail };
    }
    return std.json.Stringify.valueAlloc(a, JsonReport{ .input = rep.input, .baseline = .{ .stdout = rep.baseline.stdout, .exit = rep.baseline.exit }, .counts = rep.counts, .score = rep.score, .mutants = ms }, .{ .whitespace = .indent_2 });
}
