//! pitx CLI: parses flags and runs pitx.analyze on one .ll file.
const std = @import("std");
const pitx = @import("pitx");
const mem = std.mem;

const usage =
    \\usage: pitx <ll> [--harness lli|link] [--runtime runtime.c] [--gc-dir compiler/gc] [--gc NAME]
    \\            [--ops icmp-flip,arith-swap] [--skip-functions PATTERNS] [--all-functions]
    \\            [--timeout SECONDS] [--jobs N] [--json OUT]
    \\
;

pub fn main(init: std.process.Init) !u8 {
    const a = init.arena.allocator();
    const io = init.io;
    const args = try init.minimal.args.toSlice(a);
    var stdout_buf: [4096]u8 = undefined;
    var stdout_w = std.Io.File.stdout().writer(io, &stdout_buf);
    const out = &stdout_w.interface;
    var stderr_buf: [1024]u8 = undefined;
    var stderr_w = std.Io.File.stderr().writer(io, &stderr_buf);
    const err = &stderr_w.interface;
    defer out.flush() catch {};
    defer err.flush() catch {};

    var ll: ?[]const u8 = null;
    var harness: []const u8 = "lli";
    var runtime: ?[]const u8 = null;
    var gc_dir: ?[]const u8 = null;
    var gc: []const u8 = "perceus";
    var ops_arg: []const u8 = "icmp-flip,arith-swap";
    var skip: ?[]const u8 = null;
    var all_functions = false;
    var timeout_s: f64 = 10.0;
    var jobs: usize = 1;
    var json_out: ?[]const u8 = null;

    var i: usize = 1;
    while (i < args.len) : (i += 1) {
        var arg: []const u8 = args[i];
        var inline_val: ?[]const u8 = null;
        if (mem.startsWith(u8, arg, "--")) if (mem.indexOfScalar(u8, arg, '=')) |eq| {
            inline_val = arg[eq + 1 ..];
            arg = arg[0..eq];
        };
        if (mem.eql(u8, arg, "-h") or mem.eql(u8, arg, "--help")) {
            try out.writeAll(usage);
            return 0;
        }
        if (mem.eql(u8, arg, "--all-functions")) {
            all_functions = true;
            continue;
        }
        if (!mem.startsWith(u8, arg, "--")) {
            if (ll != null) {
                try err.writeAll(usage);
                return 2;
            }
            ll = arg;
            continue;
        }
        const v: []const u8 = inline_val orelse blk: {
            i += 1;
            if (i >= args.len) {
                try err.print("pitx: {s} needs a value\n", .{arg});
                return 2;
            }
            break :blk args[i];
        };
        if (mem.eql(u8, arg, "--harness")) harness = v
        else if (mem.eql(u8, arg, "--runtime")) runtime = v
        else if (mem.eql(u8, arg, "--gc-dir")) gc_dir = v
        else if (mem.eql(u8, arg, "--gc")) gc = v
        else if (mem.eql(u8, arg, "--ops")) ops_arg = v
        else if (mem.eql(u8, arg, "--skip-functions")) skip = v
        else if (mem.eql(u8, arg, "--timeout")) timeout_s = try std.fmt.parseFloat(f64, v)
        else if (mem.eql(u8, arg, "--jobs")) jobs = try std.fmt.parseInt(usize, v, 10)
        else if (mem.eql(u8, arg, "--json")) json_out = v
        else {
            try err.print("pitx: unknown option {s}\n{s}", .{ arg, usage });
            return 2;
        }
    }
    const input = ll orelse {
        try err.writeAll(usage);
        return 2;
    };

    var ops: std.ArrayList(pitx.Operator) = .empty;
    var oi = mem.splitScalar(u8, ops_arg, ',');
    while (oi.next()) |name| try ops.append(a, pitx.operatorByName(name) orelse {
        try err.print("pitx: unknown operator {s}\n", .{name});
        return 2;
    });

    const timeout_ms: i64 = @intFromFloat(timeout_s * 1000);
    const h: pitx.Harness = if (mem.eql(u8, harness, "lli"))
        .{ .lli = .{ .timeout_ms = timeout_ms } }
    else if (mem.eql(u8, harness, "link"))
        .{ .link = .{ .runtime = runtime orelse return usageErr(err, "--runtime"), .gc_dir = gc_dir orelse return usageErr(err, "--gc-dir"), .gc = gc, .timeout_ms = timeout_ms } }
    else {
        try err.print("pitx: --harness must be lli or link\n", .{});
        return 2;
    };

    const ctx = try pitx.Ctx.init(a, io, init.environ_map);
    var diag: pitx.AnalyzeError = .{};
    const rep = pitx.analyze(a, ctx, input, h, .{
        .enum_opts = .{ .ops = ops.items, .skip = if (skip) |s| .{ .patterns = s } else null, .only_reachable = !all_functions },
        .jobs = jobs,
    }, &diag) catch |e| switch (e) {
        error.InvalidInput => {
            try err.print("pitx: invalid input IR: {s}\n", .{diag.invalid_input});
            return 2;
        },
        else => return e,
    };
    try out.print("{s}\n", .{try pitx.textReport(a, rep)});
    if (json_out) |p| try std.Io.Dir.cwd().writeFile(io, .{ .sub_path = p, .data = try pitx.jsonReport(a, rep) });
    return 0;
}

fn usageErr(err: *std.Io.Writer, flag: []const u8) u8 {
    err.print("pitx: --harness link needs {s}\n", .{flag}) catch {};
    return 2;
}
