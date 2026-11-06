//! Shared host allocation and environment helpers.
//! Only the standalone host exports runtime callbacks; runtime_host uses KotlinX's C symbols.
const std = @import("std");
const builtin = @import("builtin");
const c = std.c;

extern fn rt_panic(msg: [*:0]const u8) noreturn;
pub fn oom() noreturn { rt_panic("OutOfMemoryError"); }
pub fn malloc(n: usize) *anyopaque { return c.malloc(n) orelse oom(); }
pub fn calloc(n: usize) *anyopaque { return c.calloc(1, n) orelse oom(); }
pub fn realloc(p: ?*anyopaque, n: usize) *anyopaque {
    const q: ?*anyopaque = if (p) |ptr| ptr else null;
    return c.realloc(q, n) orelse oom();
}
pub fn free(p: ?*anyopaque) void { c.free(p); }
pub fn getenv_i64(name: [*:0]const u8, default_val: i64) i64 {
    const e = c.getenv(name) orelse return default_val;
    if (e[0] == 0) return default_val;
    return std.fmt.parseInt(i64, std.mem.span(e), 10) catch default_val;
}
pub fn getenv_present(name: [*:0]const u8) bool {
    const e = c.getenv(name) orelse return false;
    return e[0] != 0 and e[0] != '0';
}
pub fn peakRssKb() i64 {
    const ru = std.posix.getrusage(c.rusage.SELF);
    if (builtin.os.tag == .macos) return @divTrunc(ru.maxrss, 1024);
    return ru.maxrss;
}
