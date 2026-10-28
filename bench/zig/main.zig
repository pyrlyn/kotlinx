const std = @import("std");
const c = std.c;

pub fn main() void {
    var info: c.mach_timebase_info_data = undefined;
    _ = c.mach_timebase_info(&info);
    const n: i64 = 10_000_000;
    var sum: i64 = 0;
    const t0 = c.mach_absolute_time();
    var i: i64 = 1;
    while (i <= n) : (i += 1) {
        const sq = i * i;
        if (@rem(i, 3) == 0) sum += sq else sum -= sq;
    }
    const t1 = c.mach_absolute_time();
    const nanos = (t1 - t0) * info.numer / info.denom;
    std.debug.print("sum={d} nanos={d}\n", .{ sum, nanos });
}
