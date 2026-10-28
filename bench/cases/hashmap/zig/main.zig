// hashmap: 1M i32->i64 inserts, then 3M lookups (1/3 hits) with std.AutoHashMapUnmanaged.
const std = @import("std");
const c = std.c;
fn nowNs(info: c.mach_timebase_info_data, t0: u64) u64 {
    return (c.mach_absolute_time() - t0) * info.numer / info.denom;
}
pub fn main() !void {
    var info: c.mach_timebase_info_data = undefined;
    _ = c.mach_timebase_info(&info);
    const gpa = std.heap.c_allocator;
    const n: i32 = 1_000_000;
    const t0 = c.mach_absolute_time();
    var m: std.AutoHashMapUnmanaged(i32, i64) = .empty;
    var i: i32 = 0;
    while (i < n) : (i += 1) try m.put(gpa, i * 3, @as(i64, i) * 2);
    var sum: i64 = 0;
    var miss: i64 = 0;
    var j: i32 = 0;
    while (j < 3 * n) : (j += 1) {
        if (m.contains(j)) sum += m.get(j).? else miss += 1;
    }
    const checksum = sum + miss * 1_000_003 + @as(i64, m.count());
    std.debug.print("checksum={d} nanos={d}\n", .{ checksum, nowNs(info, t0) });
}
