// alloc: ROUNDS x N individually heap-allocated small objects, summed, then freed.
const std = @import("std");
const c = std.c;
const P = struct { x: i64, y: i64 };
pub fn main() !void {
    var info: c.mach_timebase_info_data = undefined;
    _ = c.mach_timebase_info(&info);
    const gpa = std.heap.c_allocator;
    const rounds: i64 = 50;
    const n: i64 = 200_000;
    const t0 = c.mach_absolute_time();
    var checksum: i64 = 0;
    var r: i64 = 0;
    while (r < rounds) : (r += 1) {
        var v: std.ArrayList(*P) = .empty;
        var i: i64 = 0;
        while (i < n) : (i += 1) {
            const p = try gpa.create(P);
            p.* = .{ .x = i + r, .y = @mod(i * 7, 1000) };
            try v.append(gpa, p);
        }
        for (v.items) |p| checksum += p.x * 31 + p.y;
        for (v.items) |p| gpa.destroy(p);
        v.deinit(gpa);
    }
    const t1 = c.mach_absolute_time();
    std.debug.print("checksum={d} nanos={d}\n", .{ checksum, (t1 - t0) * info.numer / info.denom });
}
