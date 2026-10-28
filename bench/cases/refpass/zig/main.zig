// refpass: 30M calls passing object pointers, plus a pointer stored in a mutable field (no ownership tracking).
const std = @import("std");
const c = std.c;
const P = struct { x: i64, y: i64 };
const Holder = struct { p: *P };
fn score(a: *const P, b: *const P) i64 { return a.x * b.y - a.y * b.x; }
fn pick(a: *P, b: *P, k: i64) *P { return if (k & 1 == 0) a else b; }
pub fn main() !void {
    var info: c.mach_timebase_info_data = undefined;
    _ = c.mach_timebase_info(&info);
    const gpa = std.heap.c_allocator;
    var pool: std.ArrayList(*P) = .empty;
    var i: i64 = 0;
    while (i < 1000) : (i += 1) { const p = try gpa.create(P); p.* = .{ .x = i, .y = @mod(i * 7, 13) }; try pool.append(gpa, p); }
    var h = Holder{ .p = pool.items[0] };
    const t0 = c.mach_absolute_time();
    var checksum: i64 = 0;
    var k: i64 = 0;
    while (k < 30_000_000) : (k += 1) {
        const a = pool.items[@intCast(@mod(k, 1000))];
        const b = h.p;
        const cc = pick(a, b, k);
        checksum += score(a, cc);
        if (@mod(k, 4) == 0) h.p = a;
    }
    const t1 = c.mach_absolute_time();
    for (pool.items) |p| gpa.destroy(p);
    pool.deinit(gpa);
    std.debug.print("checksum={d} nanos={d}\n", .{ checksum, (t1 - t0) * info.numer / info.denom });
}
