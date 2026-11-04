//! pitx tests: operator unit tests (exact IR), site enumeration, and runner classification on fixtures.
//! Run: zig build test (from pitx/; needs llvm-as and lli from the repo's mise LLVM tools).
const std = @import("std");
const pitx = @import("pitx");
const t = std.testing;
const mem = std.mem;

const fix_dir = "tests/fixtures/";

const Env = struct {
    arena: std.heap.ArenaAllocator,
    ctx: pitx.Ctx,
    fn init() !*Env {
        const e = try std.heap.page_allocator.create(Env);
        e.arena = .init(std.heap.page_allocator);
        e.ctx = try pitx.Ctx.init(e.arena.allocator(), t.io, null);
        return e;
    }
    fn deinit(e: *Env) void {
        e.arena.deinit();
        std.heap.page_allocator.destroy(e);
    }
    fn a(e: *Env) std.mem.Allocator {
        return e.arena.allocator();
    }
    fn read(e: *Env, name: []const u8) ![]u8 {
        return std.Io.Dir.cwd().readFileAlloc(t.io, try fx(e.a(), name), e.a(), .unlimited);
    }
};

fn fx(a: std.mem.Allocator, name: []const u8) ![]const u8 {
    return std.fmt.allocPrint(a, fix_dir ++ "{s}", .{name});
}

fn lli(ms: i64) pitx.Harness {
    return .{ .lli = .{ .timeout_ms = ms } };
}

// ---------------------------------------------------------------- icmp-flip

test "icmp-flip: all predicates flip to negation" {
    var arena = std.heap.ArenaAllocator.init(t.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    for (pitx.icmp_predicates) |p| {
        const q = pitx.icmpFlip(p).?;
        const ir = try std.fmt.allocPrint(a, "define i1 @f(i32 %a) {{\n  %c = icmp {s} i32 %a, 0\n  ret i1 %c\n}}", .{p});
        const ms = try pitx.enumerateSites(a, ir, .{ .ops = &.{pitx.icmp_flip} });
        try t.expectEqual(1, ms.len);
        try t.expectEqualStrings(p, ms[0].original);
        try t.expectEqualStrings(q, ms[0].replacement);
        try t.expectEqual(2, ms[0].line);
        try t.expectEqualStrings("f", ms[0].function);
        const want = try std.fmt.allocPrint(a, "define i1 @f(i32 %a) {{\n  %c = icmp {s} i32 %a, 0\n  ret i1 %c\n}}", .{q});
        try t.expectEqualStrings(want, try pitx.apply(a, ir, ms[0]));
    }
}

test "icmp-flip: flip is an involution" {
    for (pitx.icmp_predicates) |p| try t.expectEqualStrings(p, pitx.icmpFlip(pitx.icmpFlip(p).?).?);
}

test "icmp-flip: fcmp and comments are not sites" {
    var arena = std.heap.ArenaAllocator.init(t.allocator);
    defer arena.deinit();
    const ir = "define i1 @f(double %a) {\n  %c = fcmp olt double %a, 0.0 ; icmp eq in a comment\n  ret i1 %c\n}";
    try t.expectEqual(0, (try pitx.enumerateSites(arena.allocator(), ir, .{})).len);
}

// ---------------------------------------------------------------- arith-swap

test "arith-swap: add to sub exact" {
    var arena = std.heap.ArenaAllocator.init(t.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    const ir = "define i32 @f(i32 %a) {\n  %r = add i32 %a, 7\n  ret i32 %r\n}";
    const ms = try pitx.enumerateSites(a, ir, .{ .ops = &.{pitx.arith_swap} });
    try t.expectEqual(1, ms.len);
    try t.expectEqualStrings("define i32 @f(i32 %a) {\n  %r = sub i32 %a, 7\n  ret i32 %r\n}", try pitx.apply(a, ir, ms[0]));
}

test "arith-swap: sub to add drops wrap flags" {
    var arena = std.heap.ArenaAllocator.init(t.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    const ir = "define i32 @f(i32 %a) {\n  %r = sub nuw nsw i32 %a, 7\n  ret i32 %r\n}";
    const ms = try pitx.enumerateSites(a, ir, .{ .ops = &.{pitx.arith_swap} });
    try t.expectEqual(1, ms.len);
    try t.expectEqualStrings("sub nuw nsw", ms[0].original);
    try t.expectEqualStrings("define i32 @f(i32 %a) {\n  %r = add i32 %a, 7\n  ret i32 %r\n}", try pitx.apply(a, ir, ms[0]));
}

test "arith-swap: float ops and mul are not sites" {
    var arena = std.heap.ArenaAllocator.init(t.allocator);
    defer arena.deinit();
    const ir = "define double @f(double %a, i32 %b) {\n  %x = fadd double %a, 1.0\n  %y = fsub double %x, 1.0\n" ++
        "  %z = mul i32 %b, 2\n  ret double %y\n}";
    try t.expectEqual(0, (try pitx.enumerateSites(arena.allocator(), ir, .{})).len);
}

// ---------------------------------------------------------------- enumeration

test "enumeration: sites in order with functions" {
    const e = try Env.init();
    defer e.deinit();
    const ms = try pitx.enumerateSites(e.a(), try e.read("cond.ll"), .{});
    try t.expectEqual(2, ms.len);
    try t.expectEqualStrings("icmp-flip", ms[0].op.name);
    try t.expectEqualStrings("max", ms[0].function);
    try t.expectEqualStrings("sgt", ms[0].original);
    try t.expectEqualStrings("icmp-flip", ms[1].op.name);
    try t.expectEqualStrings("sign", ms[1].function);
    try t.expectEqualStrings("slt", ms[1].original);
}

test "enumeration: globals and declarations are skipped" {
    var arena = std.heap.ArenaAllocator.init(t.allocator);
    defer arena.deinit();
    const ir = "@g = global i32 add (i32 1, i32 2)\ndeclare i32 @h(i32)\n";
    try t.expectEqual(0, (try pitx.enumerateSites(arena.allocator(), ir, .{})).len);
}

test "enumeration: skip functions" {
    const e = try Env.init();
    defer e.deinit();
    const ms = try pitx.enumerateSites(e.a(), try e.read("neutral.ll"), .{ .skip = .{ .patterns = "^dead$" } });
    try t.expect(ms.len > 0);
    for (ms) |m| try t.expectEqualStrings("main", m.function);
}

test "enumeration: reachability skips dead functions" {
    const e = try Env.init();
    defer e.deinit();
    const ir = try e.read("neutral.ll");
    const r = try pitx.reachableFunctions(e.a(), ir);
    try t.expectEqual(3, r.count());
    for ([_][]const u8{ "main", "printf", "fmt" }) |n| try t.expect(r.contains(n));
    const ms = try pitx.enumerateSites(e.a(), ir, .{ .only_reachable = true });
    try t.expect(ms.len > 0);
    for (ms) |m| try t.expectEqualStrings("main", m.function);
}

test "enumeration: reachability through globals" {
    var arena = std.heap.ArenaAllocator.init(t.allocator);
    defer arena.deinit();
    const ir = "@vt = constant [1 x ptr] [ptr @impl]\n" ++
        "define i32 @impl() {\n  %a = add i32 1, 2\n  ret i32 %a\n}\n" ++
        "define i32 @unused() {\n  %a = add i32 1, 2\n  ret i32 %a\n}\n" ++
        "define i32 @main() {\n  %f = load ptr, ptr @vt\n  %r = call i32 %f()\n  ret i32 %r\n}\n";
    const ms = try pitx.enumerateSites(arena.allocator(), ir, .{ .only_reachable = true });
    try t.expectEqual(1, ms.len);
    try t.expectEqualStrings("impl", ms[0].function);
}

test "enumeration: debug location mapping" {
    var arena = std.heap.ArenaAllocator.init(t.allocator);
    defer arena.deinit();
    const ir = "define i32 @f(i32 %a) !dbg !3 {\n  %r = add i32 %a, 1, !dbg !7\n  ret i32 %r\n}\n" ++
        "!7 = !DILocation(line: 42, column: 5, scope: !3)\n";
    const ms = try pitx.enumerateSites(arena.allocator(), ir, .{});
    try t.expectEqual(1, ms.len);
    try t.expectEqualStrings("line 42", ms[0].loc);
}

test "enumeration: analyze does not touch the input" {
    const e = try Env.init();
    defer e.deinit();
    const before = try e.read("arith.ll");
    _ = try pitx.analyze(e.a(), e.ctx, try fx(e.a(), "arith.ll"), lli(5000), .{}, null);
    try t.expectEqualStrings(before, try e.read("arith.ll"));
}

// ---------------------------------------------------------------- runner classification

const Exp = struct { op: []const u8, function: []const u8, original: []const u8, status: pitx.Status };

fn check(name: []const u8, want: []const Exp) !pitx.Report {
    const e = try Env.init();
    errdefer e.deinit();
    const rep = try pitx.analyze(e.a(), e.ctx, try fx(e.a(), name), lli(2000), .{}, null);
    try t.expectEqual(want.len, rep.results.len);
    for (want, rep.results) |w, r| {
        try t.expectEqualStrings(w.op, r.mutant.op.name);
        try t.expectEqualStrings(w.function, r.mutant.function);
        try t.expectEqualStrings(w.original, r.mutant.original);
        try t.expectEqual(w.status, r.status);
    }
    _ = try pitx.jsonReport(e.a(), rep); // report serializes
    return rep; // the Env (arena) is intentionally leaked by callers that inspect `rep`; page-backed
}

test "classification: cond" {
    _ = try check("cond.ll", &.{ .{ .op = "icmp-flip", .function = "max", .original = "sgt", .status = .caught }, .{ .op = "icmp-flip", .function = "sign", .original = "slt", .status = .caught } });
}

const arith_exp = [_]Exp{
    .{ .op = "arith-swap", .function = "f", .original = "add nsw", .status = .caught },
    .{ .op = "arith-swap", .function = "f", .original = "sub", .status = .caught },
    .{ .op = "arith-swap", .function = "f", .original = "add", .status = .survived }, // x+0 == x-0: equivalent
};

test "classification: arith" {
    _ = try check("arith.ll", &arith_exp);
}

test "classification: memory" {
    _ = try check("memory.ll", &.{ .{ .op = "arith-swap", .function = "main", .original = "add", .status = .caught }, .{ .op = "icmp-flip", .function = "main", .original = "ult", .status = .caught } });
}

test "classification: exit code only" {
    _ = try check("exitcode.ll", &.{.{ .op = "arith-swap", .function = "main", .original = "sub", .status = .caught }});
}

test "classification: timeout" {
    _ = try check("loop_timeout.ll", &.{ .{ .op = "arith-swap", .function = "main", .original = "add", .status = .timeout }, .{ .op = "icmp-flip", .function = "main", .original = "ne", .status = .survived } });
}

test "classification: neutral mutants are no false positives" {
    const rep = try check("neutral.ll", &.{
        .{ .op = "icmp-flip", .function = "dead", .original = "eq", .status = .survived },
        .{ .op = "arith-swap", .function = "dead", .original = "add", .status = .survived },
        .{ .op = "icmp-flip", .function = "main", .original = "slt", .status = .survived },
        .{ .op = "icmp-flip", .function = "main", .original = "eq", .status = .survived },
    });
    try t.expectEqual(0, rep.counts.caught);
    try t.expectEqual(0.0, rep.score.?);
}

test "classification: score" {
    const rep = try check("arith.ll", &arith_exp);
    try t.expectEqualDeep(pitx.Counts{ .caught = 2, .survived = 1, .timeout = 0, .invalid = 0 }, rep.counts);
    try t.expectApproxEqAbs(@as(f64, 2.0 / 3.0), rep.score.?, 1e-9);
}

// ---------------------------------------------------------------- invalid IR

test "invalid: inputs are rejected" {
    const e = try Env.init();
    defer e.deinit();
    for ([_][]const u8{ "invalid_syntax.ll", "invalid_types.ll" }) |name| {
        var diag: pitx.AnalyzeError = .{};
        try t.expectError(error.InvalidInput, pitx.analyze(e.a(), e.ctx, try fx(e.a(), name), lli(2000), .{}, &diag));
        try t.expect(mem.indexOf(u8, diag.invalid_input, "error:") != null);
    }
}

fn bogusFind(code: []const u8) ?pitx.Site {
    var s = pitx.findIcmp(code) orelse return null;
    s.replacement = "bogus";
    return s;
}

test "invalid: a mutant producing invalid IR is invalid, not caught" {
    const e = try Env.init();
    defer e.deinit();
    const bogus: pitx.Operator = .{ .name = "bogus", .find = bogusFind };
    const rep = try pitx.analyze(e.a(), e.ctx, try fx(e.a(), "cond.ll"), lli(2000), .{ .enum_opts = .{ .ops = &.{bogus} } }, null);
    try t.expectEqual(2, rep.results.len);
    for (rep.results) |r| try t.expectEqual(pitx.Status.invalid, r.status);
    try t.expectEqual(null, rep.score);
}

test "invalid: stale site is invalid" {
    const e = try Env.init();
    defer e.deinit();
    const m: pitx.Mutant = .{ .id = 0, .op = pitx.arith_swap, .line = 1, .function = "main", .original = "sub", .replacement = "add" }; // line 1 is a comment
    const r = try pitx.runMutant(e.a(), e.ctx, try e.read("exitcode.ll"), m, lli(2000), .{ .stdout = "", .exit = 5 });
    try t.expectEqual(pitx.Status.invalid, r.status);
}

// ---------------------------------------------------------------- reports

test "report: json and text" {
    const e = try Env.init();
    defer e.deinit();
    const rep = try pitx.analyze(e.a(), e.ctx, try fx(e.a(), "arith.ll"), lli(5000), .{}, null);
    const js = try pitx.jsonReport(e.a(), rep);
    const parsed = try std.json.parseFromSliceLeaky(std.json.Value, e.a(), js, .{});
    const base = parsed.object.get("baseline").?.object;
    try t.expectEqualStrings("24\n", base.get("stdout").?.string);
    try t.expectEqual(0, base.get("exit").?.integer);
    try t.expectEqual(3, parsed.object.get("mutants").?.array.items.len);
    const txt = try pitx.textReport(e.a(), rep);
    try t.expect(mem.indexOf(u8, txt, "  SURVIVED #2 arith-swap @f") != null);
    try t.expect(mem.indexOf(u8, txt, "score 67%") != null);
}
