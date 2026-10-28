// closures: Zig has no closures; the same map/filter/sum/forEach pipeline as explicit loops
// building new lists each round (1M i64, 20 rounds).
const std = @import("std");
const c = std.c;
fn nowNs(info: c.mach_timebase_info_data, t0: u64) u64 {
    return (c.mach_absolute_time() - t0) * info.numer / info.denom;
}
pub fn main() !void {
    var info: c.mach_timebase_info_data = undefined;
    _ = c.mach_timebase_info(&info);
    const gpa = std.heap.c_allocator;
    const n: usize = 1_000_000;
    var xs: std.ArrayList(i64) = .empty;
    for (0..n) |i| try xs.append(gpa, @intCast(i));
    const t0 = c.mach_absolute_time();
    var total: i64 = 0;
    var r: i64 = 0;
    while (r < 20) : (r += 1) {
        const k = @mod(r, 5) + 1;
        var ys: std.ArrayList(i64) = .empty;
        for (xs.items) |it| try ys.append(gpa, it * k + r);
        var zs: std.ArrayList(i64) = .empty;
        for (ys.items) |x| if (@mod(x, 3) == 0) try zs.append(gpa, x);
        var s: i64 = 0;
        for (zs.items) |it| s += it & 1023;
        total += s;
        for (zs.items) |it| total += @mod(it, 7);
        ys.deinit(gpa);
        zs.deinit(gpa);
    }
    std.debug.print("checksum={d} nanos={d}\n", .{ total, nowNs(info, t0) });
}
