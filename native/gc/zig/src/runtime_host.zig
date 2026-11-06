//! Host adapter for linking Zig collectors with KotlinX `runtime.c`.
//! Provides the same *call* surface as `host.zig` (malloc, rt_nanos, …) but does
//! **not** define `rt_nanos` / `rt_panic` / `rt_gc_note_pause` / `rt_rel_draining` /
//! `rt_relo_owner` / `rt_trav_mode` — those come from `compiler/runtime/runtime.c`.
//! Standalone tests keep using `host.zig` (which exports stubs).
const std = @import("std");
const builtin = @import("builtin");
const c = std.c;

pub extern var rt_rel_draining: c_int;
pub extern var rt_relo_owner: c_int;
pub extern threadlocal var rt_trav_mode: c_int;

pub extern fn rt_nanos() i64;
pub extern fn rt_panic(msg: [*:0]const u8) noreturn;
pub extern fn rt_gc_note_pause(pause_ns: i64) void;

pub fn oom() noreturn {
    rt_panic("OutOfMemoryError");
}

pub fn malloc(n: usize) *anyopaque {
    return c.malloc(n) orelse oom();
}

pub fn calloc(n: usize) *anyopaque {
    return c.calloc(1, n) orelse oom();
}

pub fn realloc(p: ?*anyopaque, n: usize) *anyopaque {
    return c.realloc(p, n) orelse if (n == 0) unreachable else oom();
}

pub fn free(p: ?*anyopaque) void {
    c.free(p);
}

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
