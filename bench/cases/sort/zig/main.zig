// sort: fill 1M i64 from an LCG and sort them with the same hand-written quicksort.
const std = @import("std");
const c = std.c;
fn nowNs(info: c.mach_timebase_info_data, t0: u64) u64 {
    return (c.mach_absolute_time() - t0) * info.numer / info.denom;
}
fn qsort(a: []i64, lo: i32, hi: i32) void {
    if (lo >= hi) return;
    const p = a[@as(u32, @bitCast(lo + hi)) >> 1];
    var i = lo;
    var j = hi;
    while (i <= j) {
        while (a[@intCast(i)] < p) i += 1;
        while (a[@intCast(j)] > p) j -= 1;
        if (i <= j) {
            const t = a[@intCast(i)];
            a[@intCast(i)] = a[@intCast(j)];
            a[@intCast(j)] = t;
            i += 1;
            j -= 1;
        }
    }
    if (lo < j) qsort(a, lo, j);
    if (i < hi) qsort(a, i, hi);
}
pub fn main() !void {
    var info: c.mach_timebase_info_data = undefined;
    _ = c.mach_timebase_info(&info);
    const n: usize = 1_000_000;
    const t0 = c.mach_absolute_time();
    const a = try std.heap.c_allocator.alloc(i64, n);
    var x: i64 = 12345;
    for (0..n) |i| {
        x = x *% 6364136223846793005 +% 1442695040888963407;
        a[i] = @bitCast(@as(u64, @bitCast(x)) >> 20);
    }
    qsort(a, 0, @intCast(n - 1));
    var checksum: i64 = 0;
    var bad: i64 = 0;
    for (0..n) |i| {
        checksum = checksum *% 31 +% a[i];
        if (i > 0 and a[i - 1] > a[i]) bad += 1;
    }
    checksum +%= bad * 1_000_000_007;
    std.debug.print("checksum={d} nanos={d}\n", .{ checksum, nowNs(info, t0) });
}
