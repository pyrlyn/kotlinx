//! Standalone host for symbols `kxgc.h` says the runtime provides.
//! KotlinX supplies these callbacks from runtime.c; tests/benches use this host.
const std = @import("std");
const builtin = @import("builtin");
const c = std.c;
const util = @import("lib/host_util.zig");
pub const oom = util.oom;
pub const malloc = util.malloc;
pub const calloc = util.calloc;
pub const realloc = util.realloc;
pub const free = util.free;
pub const getenv_i64 = util.getenv_i64;
pub const getenv_present = util.getenv_present;
pub const peakRssKb = util.peakRssKb;

pub export var rt_rel_draining: c_int = 0;
pub export var rt_relo_owner: c_int = 0;
pub export threadlocal var rt_trav_mode: c_int = 0;
var gc_pauses: [*]i64 = undefined;
var gc_pauses_len: usize = 0;
var gc_pauses_cap: usize = 0;
extern "c" fn clock_gettime_nsec_np(clock_id: c.clockid_t) u64;

pub export fn rt_nanos() i64 {
    if (comptime builtin.os.tag == .macos) {
        const nsec = clock_gettime_nsec_np(.UPTIME_RAW);
        if (nsec == 0) c.abort();
        return @intCast(nsec);
    }
    var ts: c.timespec = .{ .sec = 0, .nsec = 0 };
    if (c.clock_gettime(.MONOTONIC, &ts) != 0) c.abort();
    return @as(i64, ts.sec) * 1_000_000_000 + @as(i64, ts.nsec);
}
pub export fn rt_panic(msg: [*:0]const u8) noreturn {
    var buf: [512]u8 = undefined;
    const line = std.fmt.bufPrint(&buf, "Exception in thread \"main\" java.lang.IllegalStateException: {s}\n", .{std.mem.span(msg)}) catch c.abort();
    _ = c.write(2, line.ptr, line.len);
    c.exit(1);
}
pub export fn rt_gc_note_pause(pause_ns: i64) void {
    if (gc_pauses_len == gc_pauses_cap) {
        const cap = if (gc_pauses_cap == 0) @as(usize, 1024) else gc_pauses_cap * 2;
        const raw = c.realloc(if (gc_pauses_cap == 0) null else @ptrCast(gc_pauses), cap * @sizeOf(i64));
        if (raw == null) return;
        gc_pauses = @ptrCast(@alignCast(raw.?));
        gc_pauses_cap = cap;
    }
    gc_pauses[gc_pauses_len] = pause_ns;
    gc_pauses_len += 1;
}
