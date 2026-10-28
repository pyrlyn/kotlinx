// dispatch: interface calls through a vtable (type-erased pointer + function table), 1000 shapes x 20k rounds.
const std = @import("std");
const c = std.c;
fn nowNs(info: c.mach_timebase_info_data, t0: u64) u64 {
    return (c.mach_absolute_time() - t0) * info.numer / info.denom;
}
const VTable = struct { area: *const fn (*const anyopaque) i64, scale: *const fn (*const anyopaque, i64) i64 };
const Shape = struct { ptr: *const anyopaque, vt: *const VTable };
const Square = struct {
    s: i64,
    fn area(p: *const anyopaque) i64 { const self: *const Square = @ptrCast(@alignCast(p)); return self.s * self.s; }
    fn scale(p: *const anyopaque, k: i64) i64 { const self: *const Square = @ptrCast(@alignCast(p)); return self.s * k; }
    const vt = VTable{ .area = area, .scale = scale };
};
const Rect = struct {
    w: i64, h: i64,
    fn area(p: *const anyopaque) i64 { const self: *const Rect = @ptrCast(@alignCast(p)); return self.w * self.h; }
    fn scale(p: *const anyopaque, k: i64) i64 { const self: *const Rect = @ptrCast(@alignCast(p)); return (self.w + self.h) * k; }
    const vt = VTable{ .area = area, .scale = scale };
};
const Tri = struct {
    b: i64, h: i64,
    fn area(p: *const anyopaque) i64 { const self: *const Tri = @ptrCast(@alignCast(p)); return @divTrunc(self.b * self.h, 2); }
    fn scale(p: *const anyopaque, k: i64) i64 { const self: *const Tri = @ptrCast(@alignCast(p)); return self.b * k - self.h; }
    const vt = VTable{ .area = area, .scale = scale };
};
pub fn main() !void {
    var info: c.mach_timebase_info_data = undefined;
    _ = c.mach_timebase_info(&info);
    const gpa = std.heap.c_allocator;
    var shapes: std.ArrayList(Shape) = .empty;
    var i: i64 = 0;
    while (i < 1000) : (i += 1) {
        switch (@mod(i, 3)) {
            0 => { const p = try gpa.create(Square); p.* = .{ .s = i }; try shapes.append(gpa, .{ .ptr = p, .vt = &Square.vt }); },
            1 => { const p = try gpa.create(Rect); p.* = .{ .w = i, .h = i + 1 }; try shapes.append(gpa, .{ .ptr = p, .vt = &Rect.vt }); },
            else => { const p = try gpa.create(Tri); p.* = .{ .b = i, .h = i + 2 }; try shapes.append(gpa, .{ .ptr = p, .vt = &Tri.vt }); },
        }
    }
    std.mem.doNotOptimizeAway(&shapes);
    const t0 = c.mach_absolute_time();
    var sum: i64 = 0;
    var r: i64 = 0;
    while (r < 20_000) : (r += 1) {
        const k = r & 7;
        for (shapes.items) |s| sum += s.vt.area(s.ptr) + s.vt.scale(s.ptr, k);
    }
    std.debug.print("checksum={d} nanos={d}\n", .{ sum, nowNs(info, t0) });
}
