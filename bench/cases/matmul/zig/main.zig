// matmul: 256x256 f64 matrix multiply (i-k-j order), 4 repetitions.
const std = @import("std");
const c = std.c;
fn nowNs(info: c.mach_timebase_info_data, t0: u64) u64 {
    return (c.mach_absolute_time() - t0) * info.numer / info.denom;
}
pub fn main() !void {
    var info: c.mach_timebase_info_data = undefined;
    _ = c.mach_timebase_info(&info);
    const gpa = std.heap.c_allocator;
    const n: usize = 256;
    const a = try gpa.alloc(f64, n * n);
    const b = try gpa.alloc(f64, n * n);
    const cm = try gpa.alloc(f64, n * n);
    for (0..n * n) |i| {
        a[i] = @floatFromInt(i % 7);
        b[i] = @as(f64, @floatFromInt(i % 5)) - 2.0;
    }
    const t0 = c.mach_absolute_time();
    var total: f64 = 0.0;
    for (0..4) |rep| {
        for (0..n * n) |i| cm[i] = 0.0;
        for (0..n) |i| {
            for (0..n) |k| {
                const aik = a[i * n + k] + @as(f64, @floatFromInt(rep));
                for (0..n) |j| cm[i * n + j] += aik * b[k * n + j];
            }
        }
        for (0..n * n) |i| total += cm[i];
    }
    const checksum: i64 = @intFromFloat(total);
    std.debug.print("checksum={d} nanos={d}\n", .{ checksum, nowNs(info, t0) });
}
