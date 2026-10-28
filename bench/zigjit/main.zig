//! ZigJit participant: emits the benchmark kernel as AArch64 machine code at runtime.
const std = @import("std");
const c = std.c;
const zj = @import("zigjit");

fn ns(info: c.mach_timebase_info_data, d: u64) u64 {
    return d * info.numer / info.denom;
}

pub fn main() !void {
    var info: c.mach_timebase_info_data = undefined;
    _ = c.mach_timebase_info(&info);
    const t0 = c.mach_absolute_time();
    var cb = zj.CodeBuffer.init(std.heap.c_allocator);
    defer cb.deinit();
    const a = zj.aarch64.Assembler.init(&cb);
    // x0 = n, x1 = i, x2 = sum, x3 = sq, x4 = 3, x5 = i % 3
    const loop = try a.newLabel();
    const plus = try a.newLabel();
    const next = try a.newLabel();
    try a.movz(.x2, 0, 0);
    try a.movz(.x1, 1, 0);
    try a.movz(.x4, 3, 0);
    try a.bind(loop);
    try a.mul(.x3, .x1, .x1);
    try a.word(0x9AC00C00 | (4 << 16) | (1 << 5) | 5); // sdiv x5, x1, x4
    try a.word(0x9B008000 | (4 << 16) | (1 << 10) | (5 << 5) | 5); // msub x5, x5, x4, x1
    try a.cbz(.x5, plus);
    try a.sub(.x2, .x2, .x3);
    try a.b(next);
    try a.bind(plus);
    try a.add(.x2, .x2, .x3);
    try a.bind(next);
    try a.addImm(.x1, .x1, 1);
    try a.cmp(.x1, .x0);
    try a.bcond(.le, loop);
    try a.mov(.x0, .x2);
    try a.ret();
    try cb.finalize();
    const f = try zj.JitRuntime.add(&cb);
    defer f.release();
    const kernel = f.ptr(*const fn (i64) callconv(.c) i64);
    const t1 = c.mach_absolute_time();
    const sum = kernel(10_000_000);
    const t2 = c.mach_absolute_time();
    std.debug.print("sum={d} nanos={d} codegen_nanos={d}\n", .{ sum, ns(info, t2 - t1), ns(info, t1 - t0) });
}
