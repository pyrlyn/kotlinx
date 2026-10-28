// strings: build a 2M-number CSV-like text in a byte buffer, then parse it byte by byte.
const std = @import("std");
const c = std.c;
fn nowNs(info: c.mach_timebase_info_data, t0: u64) u64 {
    return (c.mach_absolute_time() - t0) * info.numer / info.denom;
}
pub fn main() !void {
    var info: c.mach_timebase_info_data = undefined;
    _ = c.mach_timebase_info(&info);
    const gpa = std.heap.c_allocator;
    const n: i64 = 2_000_000;
    const t0 = c.mach_absolute_time();
    var sb: std.ArrayList(u8) = .empty;
    var buf: [32]u8 = undefined;
    var i: i64 = 0;
    while (i < n) : (i += 1) {
        try sb.appendSlice(gpa, try std.fmt.bufPrint(&buf, "{d}", .{i}));
        try sb.append(gpa, ',');
        if (@mod(i, 10) == 9) try sb.appendSlice(gpa, "x\n");
    }
    const s = try gpa.dupe(u8, sb.items); // Kotlin's StringBuilder.toString() copies
    var sum: i64 = 0;
    var cur: i64 = 0;
    var fields: i64 = 0;
    var lines: i64 = 0;
    for (s) |ch| {
        if (ch == ',') {
            sum += cur;
            cur = 0;
            fields += 1;
        } else if (ch == '\n') {
            lines += 1;
        } else if (ch >= '0' and ch <= '9') cur = cur * 10 + @as(i64, ch - '0');
    }
    const checksum = sum + fields * 7 + lines * 13 + @as(i64, @intCast(s.len));
    std.debug.print("checksum={d} nanos={d}\n", .{ checksum, nowNs(info, t0) });
}
