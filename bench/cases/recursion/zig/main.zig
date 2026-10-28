// recursion: naive fib(32) + deep non-tail recursion depth 100_000.
const std = @import("std");
const c = std.c;
noinline fn fib(n: i64) i64 {
    return if (n < 2) n else fib(n - 1) + fib(n - 2);
}
noinline fn deep(n: i64) i64 {
    return if (n == 0) 0 else n + deep(n - 1);
}
pub fn main() void {
    var info: c.mach_timebase_info_data = undefined;
    _ = c.mach_timebase_info(&info);
    var n: i64 = 32;
    var d: i64 = 100_000;
    std.mem.doNotOptimizeAway(&n);
    std.mem.doNotOptimizeAway(&d);
    const t0 = c.mach_absolute_time();
    const checksum = fib(n) + deep(d);
    const t1 = c.mach_absolute_time();
    std.debug.print("checksum={d} nanos={d}\n", .{ checksum, (t1 - t0) * info.numer / info.denom });
}
