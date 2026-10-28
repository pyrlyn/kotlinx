// trees: binary-trees style (depths 4..16 step 2 + one long-lived tree); malloc/free per node.
const std = @import("std");
const c = std.c;
const Tree = struct { l: ?*Tree, r: ?*Tree };
const gpa = std.heap.c_allocator;
fn make(d: i32) !*Tree {
    const t = try gpa.create(Tree);
    if (d == 0) { t.* = .{ .l = null, .r = null }; } else { t.* = .{ .l = try make(d - 1), .r = try make(d - 1) }; }
    return t;
}
fn check(t: ?*Tree) i64 { const n = t orelse return 0; return 1 + check(n.l) + check(n.r); }
fn free(t: ?*Tree) void { const n = t orelse return; free(n.l); free(n.r); gpa.destroy(n); }
pub fn main() !void {
    var info: c.mach_timebase_info_data = undefined;
    _ = c.mach_timebase_info(&info);
    const min_d: i32 = 4;
    const max_d: i32 = 16;
    const t0 = c.mach_absolute_time();
    const long_lived = try make(max_d);
    var checksum: i64 = 0;
    var d = min_d;
    while (d <= max_d) : (d += 2) {
        const iters = @as(i64, 1) << @intCast(max_d - d + min_d);
        var i: i64 = 0;
        while (i < iters) : (i += 1) { const t = try make(d); checksum += check(t); free(t); }
    }
    checksum += check(long_lived);
    free(long_lived);
    const t1 = c.mach_absolute_time();
    std.debug.print("checksum={d} nanos={d}\n", .{ checksum, (t1 - t0) * info.numer / info.denom });
}
