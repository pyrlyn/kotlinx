// free: 20 rounds; fill 200k heap objects of three sizes, replace every third (freeing the old), free all.
const std = @import("std");
const c = std.c;
const S1 = struct { a: i64 };
const S3 = struct { a: i64, b: i64, c: i64 };
const S6 = struct { a: i64, b: i64, c: i64, d: i64, e: i64, f: i64 };
const Obj = union(enum) { s1: *S1, s3: *S3, s6: *S6 };
const gpa = std.heap.c_allocator;
fn mk(i: i64) !Obj {
    switch (@mod(i, 3)) {
        0 => { const p = try gpa.create(S1); p.* = .{ .a = i }; return .{ .s1 = p }; },
        1 => { const p = try gpa.create(S3); p.* = .{ .a = i, .b = 1, .c = 2 }; return .{ .s3 = p }; },
        else => { const p = try gpa.create(S6); p.* = .{ .a = i, .b = 1, .c = 2, .d = 3, .e = 4, .f = 5 }; return .{ .s6 = p }; },
    }
}
fn v(o: Obj) i64 {
    return switch (o) { .s1 => |p| p.a, .s3 => |p| p.a + p.b + p.c, .s6 => |p| p.a + p.b + p.c + p.d + p.e + p.f };
}
fn destroy(o: Obj) void { switch (o) { .s1 => |p| gpa.destroy(p), .s3 => |p| gpa.destroy(p), .s6 => |p| gpa.destroy(p) } }
pub fn main() !void {
    var info: c.mach_timebase_info_data = undefined;
    _ = c.mach_timebase_info(&info);
    const n: i64 = 200_000;
    const t0 = c.mach_absolute_time();
    var checksum: i64 = 0;
    var r: i64 = 0;
    while (r < 20) : (r += 1) {
        var xs: std.ArrayList(Obj) = .empty;
        var i: i64 = 0;
        while (i < n) : (i += 1) try xs.append(gpa, try mk(i + r));
        i = 0;
        while (i < n) : (i += 3) { destroy(xs.items[@intCast(i)]); xs.items[@intCast(i)] = try mk(i * 7); }
        for (xs.items) |o| checksum += v(o);
        for (xs.items) |o| destroy(o);
        xs.deinit(gpa);
    }
    const t1 = c.mach_absolute_time();
    std.debug.print("checksum={d} nanos={d}\n", .{ checksum, (t1 - t0) * info.numer / info.denom });
}
