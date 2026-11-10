//! RC and tracing regions. Port of `common/regions.inc`.
//!
//! Build options mirror the C preprocessor switches: -Dleakcheck enables tagged prefixes,
//! region counters and traversal validation; -Dpool enables the RC size-class pool;
//! -Dplain-malloc selects malloc instead of the tracing pool. All default to false.
//! The `leakcheck.add()` hook (rt_leak_obj; no-op unless -Dleakcheck) counts every object alloc/free
//! in both regions in all modes, so `--leak-check` gets counts without the tagged-prefix mode.
const std = @import("std");
const c_std = std.c;
extern "c" fn fdopen(fd: c_int, mode: [*:0]const u8) ?*c_std.FILE;
fn errStream() ?*c_std.FILE { return fdopen(2, "w"); }
const kx = @import("kxgc");
const host = @import("host");
const leak = @import("leakcheck");

const options = @import("host_options");
pub const RT_LEAKCHECK: bool = options.leakcheck;
pub const RT_POOL: bool = options.pool;
pub const RT_PLAIN_MALLOC: bool = options.plain_malloc;
// ---- size-class slab pool ------------------------------------------------------------------
const RT_CLASSES: usize = 32;
const RT_SIZE_CLASS_SHIFT: u6 = 3;
const RT_SLAB_BYTES: usize = 1 << 16;

const Pool = struct {
    free_list: [RT_CLASSES + 1]?*anyopaque = @splat(null),
    ptr: usize = 0,
    end: usize = 0,

    inline fn alloc(self: *Pool, size: i64) *anyopaque {
        const cls = (@as(usize, @intCast(size)) + 7) >> RT_SIZE_CLASS_SHIFT;
        if (cls > RT_CLASSES) return host.malloc(@intCast(size));
        if (self.free_list[cls]) |p| {
            const next: *?*anyopaque = @ptrCast(@alignCast(p));
            self.free_list[cls] = next.*;
            return p;
        }
        const bytes = cls << RT_SIZE_CLASS_SHIFT;
        if (self.ptr + bytes > self.end) return self.refill(bytes);
        const p: *anyopaque = @ptrFromInt(self.ptr);
        self.ptr += bytes;
        return p;
    }

    /// Slow path of alloc, once per 64 KB slab. Out of line so that alloc, inlined into the exported
    /// rt_alloc/rt_alloc_trace, stays small: V8 inlines small wasm callees into the program's functions.
    noinline fn refill(self: *Pool, bytes: usize) *anyopaque {
        @branchHint(.cold);
        // The tail of the old slab is abandoned (as in C).
        self.ptr = @intFromPtr(host.malloc(RT_SLAB_BYTES));
        self.end = self.ptr + RT_SLAB_BYTES;
        const p: *anyopaque = @ptrFromInt(self.ptr);
        self.ptr += bytes;
        return p;
    }

    inline fn free(self: *Pool, p: *anyopaque, size: i64) void {
        const cls = (@as(usize, @intCast(size)) + 7) >> RT_SIZE_CLASS_SHIFT;
        if (cls > RT_CLASSES) {
            host.free(p);
            return;
        }
        const next: *?*anyopaque = @ptrCast(@alignCast(p));
        next.* = self.free_list[cls];
        self.free_list[cls] = p;
    }
};

var rc_pool = Pool{};
var trace_pool = Pool{};

// ---- RT_LEAKCHECK: region tags and counters ---------------------------------------------------
pub const RG_RC: i64 = 0x5243; // "RC"
pub const RG_TRACE: i64 = 0x5452; // "TR"
const RG_INDEX_RC = 0;
const RG_INDEX_TRACE = 1;
const RG_PREFIX: usize = 16; // tag word + padding, keeps 16-byte alignment

pub const Counters = struct {
    alloc: [2]i64 = .{ 0, 0 },
    free: [2]i64 = .{ 0, 0 },
    visits: i64 = 0,
    visits_outside: i64 = 0,
    runs: i64 = 0,
};
pub var counters: Counters = .{};
var report_registered = false;
extern "c" fn atexit(f: *const fn () callconv(.c) void) c_int;
extern "c" fn fprintf(stream: ?*c_std.FILE, fmt: [*:0]const u8, ...) c_int;
extern "c" fn fflush(stream: ?*c_std.FILE) c_int;

fn ensureReport() void {
    if (report_registered) return;
    report_registered = true;
    _ = atexit(&reportAtExit);
}
fn reportAtExit() callconv(.c) void {
    report();
}

/// C uses destructor(200) so this report follows runtime.c leak-check output.
pub fn report() void {
    const counters_v = counters;
    _ = fprintf(errStream(), "region: rc allocated=%lld freed=%lld traced=0\n", counters_v.alloc[RG_INDEX_RC], counters_v.free[RG_INDEX_RC]);
    _ = fprintf(errStream(), "region: trace allocated=%lld freed=%lld\n", counters_v.alloc[RG_INDEX_TRACE], counters_v.free[RG_INDEX_TRACE]);
    _ = fprintf(errStream(), "region: collector runs=%lld visits=%lld outside-trace=%lld\n", counters_v.runs, counters_v.visits, counters_v.visits_outside);
    if (counters_v.visits_outside != 0) {
        _ = fprintf(errStream(), "region: FAIL collector touched RC-region objects\n");
        _ = fflush(errStream());
        c_std._exit(4);
    }
}
inline fn rgTag(raw: *anyopaque, tag: i64) *anyopaque {
    ensureReport();
    const w: *i64 = @ptrCast(@alignCast(raw));
    w.* = tag;
    return @ptrFromInt(@intFromPtr(raw) + RG_PREFIX);
}
inline fn rgRaw(p: *anyopaque, tag: i64) *anyopaque {
    const raw: *i64 = @ptrFromInt(@intFromPtr(p) - RG_PREFIX);
    if (raw.* != tag) {
        _ = fprintf(errStream(), "region: object %p freed into the wrong region\n", p);
        _ = fflush(errStream());
        c_std.abort();
    }
    raw.* = 0;
    return raw;
}
inline fn rgIsTrace(p: *anyopaque) bool {
    const raw: *const i64 = @ptrFromInt(@intFromPtr(p) - RG_PREFIX);
    return raw.* == RG_TRACE;
}

/// CC_VISIT(p): call once per object the collector traverses (C: trial_deletion mark_gray loop).
/// No-op unless RT_LEAKCHECK.
pub inline fn visit(p: *anyopaque) void {
    if (!RT_LEAKCHECK) return;
    counters.visits += 1;
    if (!rgIsTrace(p)) counters.visits_outside += 1;
}
/// Counter C declares (cc_runs) but never increments; available for collectors that want it.
pub inline fn noteRun() void {
    if (RT_LEAKCHECK) counters.runs += 1;
}

// ---- region entry points ----------------------------------------------------------------------
pub fn rtAlloc(size: i64) *anyopaque {
    if (RT_LEAKCHECK) {
        leak.add(1);
        counters.alloc[RG_INDEX_RC] += 1;
        return rgTag(host.malloc(@as(usize, @intCast(size)) + RG_PREFIX), RG_RC);
    }
    if (RT_POOL) { leak.add(1); return rc_pool.alloc(size); }
    return host.malloc(@intCast(size));
}

pub fn rtFree(p: ?*anyopaque, size: i64) void {
    if (RT_LEAKCHECK) {
        leak.add(-1);
        counters.free[RG_INDEX_RC] += 1;
        host.free(rgRaw(p.?, RG_RC));
        return;
    }
    leak.add(-1);
    if (RT_POOL) {
        if (p) |q| rc_pool.free(q, size);
        return;
    }
    host.free(p);
}

pub inline fn rtAllocTrace(size: i64) *anyopaque {
    if (RT_LEAKCHECK) {
        leak.add(1);
        counters.alloc[RG_INDEX_TRACE] += 1;
        return rgTag(host.malloc(@as(usize, @intCast(size)) + RG_PREFIX), RG_TRACE);
    }
    if (RT_PLAIN_MALLOC) return rtAlloc(size);
    leak.add(1);
    return trace_pool.alloc(size);
}

pub inline fn rtFreeTrace(p: ?*anyopaque, size: i64) void {
    if (RT_LEAKCHECK) {
        leak.add(-1);
        counters.free[RG_INDEX_TRACE] += 1;
        host.free(rgRaw(p.?, RG_TRACE));
        return;
    }
    if (RT_PLAIN_MALLOC) return rtFree(p, size);
    leak.add(-1);
    if (p) |q| trace_pool.free(q, size);
}

// ---- runtime storage that may hold references: plain malloc ---------------------------------
pub fn memAlloc(n: usize) ?*anyopaque {
    return host.malloc(n);
}
pub fn memCalloc(n: usize) ?*anyopaque {
    return host.calloc(n);
}
pub fn memRealloc(p: ?*anyopaque, n: usize) ?*anyopaque {
    if (n == 0) {
        host.free(p);
        return host.malloc(0);
    }
    return host.realloc(p, n);
}
pub fn memFree(p: ?*anyopaque) void {
    host.free(p);
}

pub fn notDeferred(what: [*:0]const u8) noreturn {
    host.rt_panic(what);
}

test "pool reuses same size class" {
    if (RT_LEAKCHECK or RT_PLAIN_MALLOC) return error.SkipZigTest;
    const a = rtAllocTrace(32);
    const b = rtAllocTrace(32);
    try std.testing.expect(a != b);
    rtFreeTrace(a, 32);
    const c = rtAllocTrace(32);
    try std.testing.expect(c == a);
    rtFreeTrace(b, 32);
    rtFreeTrace(c, 32);
}

test "pool large sizes and alignment" {
    var p = Pool{};
    const big = p.alloc(RT_CLASSES * 8 + 1);
    p.free(big, RT_CLASSES * 8 + 1);
    const x = p.alloc(8);
    try std.testing.expect(@intFromPtr(x) % 8 == 0);
    p.free(x, 8);
}

test "region tags and visit accounting" {
    const t = rgTag(host.malloc(32 + RG_PREFIX), RG_TRACE);
    const r = rgTag(host.malloc(32 + RG_PREFIX), RG_RC);
    try std.testing.expect(@intFromPtr(t) % 16 == 0);
    try std.testing.expect(rgIsTrace(t) and !rgIsTrace(r));
    host.free(rgRaw(t, RG_TRACE));
    host.free(rgRaw(r, RG_RC));
}
