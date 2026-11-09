//! compact: plain reference counting plus a small synchronous cycle collector (Bacon & Rajan trial deletion).
//! KotlinX-local (not a port of a C collector). It is the default for --target=js, where newkotlinc translates this
//! file's bitcode to JavaScript together with every program, so its size counts as much as its speed.
//!
//! Same object header and compiler contract as perceus (kxgc.h): count in the low 32 bits, color in bits 32-33,
//! buffered in bit 34. A buffered object whose count reaches zero is colored black by the generated code and left
//! here; any other dead traced object is freed with rt_free_trace. Possible roots are traced objects only (the
//! generated code and runtime.c never buffer an acyclic one, whose release would not check the buffered bit).
//!
//! A collection runs the trial deletion phases over one flat candidate set instead of root by root: MarkGray grays
//! everything the purple roots reach through cyclic references, Scan blackens what is still referenced from outside
//! that set, and the rest is garbage, freed only after every garbage object has been traversed. Each phase visits an
//! object at most once, so the cost is linear in the set however its cycles overlap.
//!
//! Shaped for the JavaScript cost model, where a 64-bit integer is a pair of 32-bit words and every global lives in
//! memory: the header is read and written as two u32 halves (never as u64); one growable array with u32 length and
//! capacity (so below 2^32 entries) is the root buffer and, above the roots, the work list of every phase; traversal
//! is iterative (a chain of 10^6 objects needs no stack); the collection runs out of line, so the mutator's call into
//! rt_cc_possible_root stays a few instructions. Objects come from libc malloc (on --target=js
//! compiler/runtime/js/libc.c): no regions, no pools, no statistics, no clock, no environment, single-threaded.
const builtin = @import("builtin");
const kx = @import("kxgc");
const host = @import("host");
const leak = @import("leakcheck");

pub const name = "compact";
pub const caps = .{ .cycles = true, .precise = true, .asan = true, .deferred = false };

comptime {
    if (builtin.cpu.arch.endian() != .little) @compileError("compact reads the header word as two little-endian halves");
}

// High half of the header word: color in bits 0-1, buffered flag in bit 2 (kxgc.h bits 32-34).
const COLOR: u32 = 3;
const BLACK: u32 = kx.BLACK;
const GRAY: u32 = kx.GRAY;
const WHITE: u32 = kx.WHITE;
const PURPLE: u32 = kx.PURPLE;
const BUF: u32 = @intCast(kx.BUF_BIT >> kx.COLOR_SHIFT);

/// Roots buffered before a collection starts. An unproductive collection (it freed less than a quarter of what
/// it traced, as when every root leads into one big live structure) doubles it, up to THRESHOLD_MAX; a productive one
/// resets it. A small minimum keeps the garbage between collections, and so the memory it holds, small.
pub const THRESHOLD_MIN: u32 = 1000;
pub const THRESHOLD_MAX: u32 = 1 << 18;
/// Threshold while a collection runs: releases of the garbage's fields buffer new roots but never start another.
const COLLECTING: u32 = ~@as(u32, 0);
pub const INITIAL_CAPACITY: u32 = 256;

/// Root buffer [0, len) outside a collection; during one, roots below and the phase's work list above.
var buf: ?[*]*anyopaque = null;
var len: u32 = 0;
var cap: u32 = 0;
var threshold: u32 = THRESHOLD_MIN;

inline fn count(o: *anyopaque) *u32 {
    return @ptrCast(@alignCast(o));
}
inline fn flags(o: *anyopaque) *u32 {
    const w: [*]u32 = @ptrCast(@alignCast(o));
    return &w[1];
}
/// The vtable pointer follows the 8-byte header word (kx.VTAB_OFFSET), also where pointers are 4 bytes (wasm32).
inline fn vtab(o: *anyopaque) *const kx.VTab {
    const b: [*]const u8 = @ptrCast(o);
    const slot: *const *const kx.VTab = @ptrCast(@alignCast(b + kx.VTAB_OFFSET));
    return slot.*;
}
inline fn isCyclic(o: *anyopaque) bool {
    return vtab(o).cyclic != null;
}

noinline fn grow() void {
    @branchHint(.cold);
    const n = if (cap == 0) INITIAL_CAPACITY else cap * 2;
    buf = @ptrCast(@alignCast(host.realloc(@ptrCast(buf), @as(usize, n) * @sizeOf(*anyopaque))));
    cap = n;
}

noinline fn push(o: *anyopaque) void {
    if (len == cap) grow();
    buf.?[len] = o;
    len += 1;
}

inline fn visitAll(o: *anyopaque, visit: kx.VisitFn) void {
    if (vtab(o).trav) |trav| trav(o, visit);
}

/// MarkGray: subtract the internal reference; gray and append the child once.
fn markGrayChild(p: ?*anyopaque) callconv(.c) void {
    const o = p orelse return;
    if (!isCyclic(o)) return;
    count(o).* -= 1;
    const f = flags(o);
    if (f.* & COLOR == GRAY) return;
    f.* = f.* & ~COLOR | GRAY;
    push(o);
}

/// ScanBlack: restore the internal reference; blacken and push the child once.
fn scanBlackChild(p: ?*anyopaque) callconv(.c) void {
    const o = p orelse return;
    if (!isCyclic(o)) return;
    count(o).* += 1;
    const f = flags(o);
    if (f.* & COLOR == BLACK) return;
    f.* &= ~COLOR;
    push(o);
}

/// CollectWhite: queue an acyclic child of the garbage for release. A cyclic child is garbage itself or live, and
/// then its count already excludes the garbage's references.
fn collectChild(p: ?*anyopaque) callconv(.c) void {
    const o = p orelse return;
    if (!isCyclic(o)) push(o);
}

/// Trial deletion over a flat candidate set: each phase is a loop over the array, and no object is traversed twice
/// in a phase.
noinline fn collect() void {
    const t = threshold;
    threshold = COLLECTING;
    // MarkRoots: unbuffer every root; keep the purple ones, grayed, compacted to the front; free the dead ones (count
    // zero, colored black by their release). Nothing is pushed, so the array stays in place.
    var p: [*]*anyopaque = buf.?;
    const e = p + len;
    var q = p;
    var k: u32 = 0;
    while (p != e) : (p += 1) {
        const o = p[0];
        const f = flags(o);
        if (f.* & COLOR == PURPLE and count(o).* != 0) {
            f.* ^= (PURPLE ^ GRAY) | BUF;
            q[0] = o;
            q += 1;
            k += 1;
        } else {
            f.* &= ~BUF;
            if (f.* & COLOR == BLACK and count(o).* == 0) vtab(o).freemem.?(o);
        }
    }
    // MarkGray, breadth first: the roots and all they reach through cyclic references become the gray set
    // S = [0, s), every reference inside S subtracted from the counts.
    len = k;
    var i: u32 = 0;
    while (i < len) : (i += 1) visitAll(buf.?[i], markGrayChild);
    const s = len;
    // Scan: an object of S still referenced from outside S is live, and so is everything it reaches: ScanBlack
    // blackens them and restores the references they hold, with its work stack above S. What stays gray is garbage.
    i = 0;
    while (i < s) : (i += 1) {
        const o = buf.?[i];
        const f = flags(o);
        if (f.* & COLOR == GRAY and count(o).* != 0) {
            f.* &= ~COLOR;
            push(o);
            while (len > s) {
                len -= 1;
                visitAll(buf.?[len], scanBlackChild);
            }
        }
    }
    // CollectWhite: compact the garbage to [0, g) and queue its acyclic children at [s, end). Collections held by
    // garbage hand their elements over (RT_TRAV_RELEASE), so its relo frees only their memory.
    host.rt_trav_mode = kx.TRAV_RELEASE;
    var g: u32 = 0;
    i = 0;
    while (i < s) : (i += 1) {
        const o = buf.?[i];
        if (flags(o).* & COLOR == GRAY) {
            buf.?[g] = o;
            g += 1;
            visitAll(o, collectChild);
        }
    }
    host.rt_trav_mode = kx.TRAV_SCAN;
    // Free the garbage, each object after its relo, then release the acyclic children. These releases never reach
    // garbage: no live object refers to it, and a collection reports a dying reference to its holder with a root
    // hint, a no-op at a count of zero, while a relo disowns every collection of its object before the object is
    // freed. A release may free a live object of S, so [g, s) is skipped, and may buffer new roots, appended at
    // [end, len).
    const end = len;
    i = 0;
    while (true) : (i += 1) {
        if (i == g) i = s;
        if (i == end) break;
        const o = buf.?[i];
        const vt = vtab(o);
        if (vt.cyclic == null) {
            if (vt.rel) |rel| rel(o);
        } else {
            if (vt.relo) |relo| relo(o);
            vt.freemem.?(o);
        }
    }
    // The new roots become the buffer.
    const m = len - end;
    @memmove(buf.?[0..m], buf.?[end..len]);
    len = m;
    threshold = if (g < s / 4) @min(t * 2, THRESHOLD_MAX) else THRESHOLD_MIN;
}

pub inline fn allocTrace(size: i64) ?*anyopaque {
    leak.add(1);
    return host.malloc(@intCast(size));
}
pub inline fn freeTrace(p: ?*anyopaque, size: i64) void {
    _ = size;
    leak.add(-1);
    host.free(p);
}
pub export fn rt_alloc(size: i64) ?*anyopaque {
    return allocTrace(size);
}
pub export fn rt_free(p: ?*anyopaque, size: i64) void {
    freeTrace(p, size);
}
pub export fn rt_alloc_trace(size: i64) ?*anyopaque {
    return allocTrace(size);
}
pub export fn rt_free_trace(p: ?*anyopaque, size: i64) void {
    freeTrace(p, size);
}
pub export fn rt_gc_mem_alloc(n: usize) ?*anyopaque {
    return host.malloc(n);
}
pub export fn rt_gc_mem_calloc(n: usize) ?*anyopaque {
    return host.calloc(n);
}
pub export fn rt_gc_mem_realloc(p: ?*anyopaque, n: usize) ?*anyopaque {
    // C realloc(p, 0) may free p and return NULL, which host.realloc reports as out of memory.
    if (n == 0) {
        host.free(p);
        return host.malloc(0);
    }
    return host.realloc(p, n);
}
pub export fn rt_gc_mem_free(p: ?*anyopaque) void {
    host.free(p);
}

pub export fn rt_cc_possible_root(p: ?*anyopaque) void {
    const o = p orelse return;
    const f = flags(o);
    const old = f.*;
    f.* = old | PURPLE | BUF;
    if (old & BUF != 0) return;
    push(o);
    if (len >= threshold and host.rt_rel_draining == 0) collect();
}
pub export fn rt_cc_dead(p: ?*anyopaque) void {
    const o = p orelse return;
    const f = flags(o);
    f.* &= ~COLOR;
    if (f.* & BUF == 0) vtab(o).freemem.?(o);
}
pub export fn rt_gc_root_hint(p: ?*anyopaque) void {
    const o = p orelse return;
    if (count(o).* != 0) rt_cc_possible_root(o);
}

pub export fn rt_gc_collect() void {
    while (len != 0 and threshold != COLLECTING and host.rt_rel_draining == 0) collect();
}
pub export fn rt_gc_name() [*:0]const u8 {
    return name;
}
/// No statistics are kept.
pub export fn rt_gc_stats(out: *kx.Stats) void {
    out.* = .{};
}

pub inline fn logInc(p: ?*anyopaque) void {
    _ = p;
    host.rt_panic("rt_gc_log_inc without deferred RC");
}
pub inline fn logDec(p: ?*anyopaque) void {
    _ = p;
    host.rt_panic("rt_gc_log_dec without deferred RC");
}
pub inline fn writeBarrier(o: ?*anyopaque) void {
    _ = o;
}
pub export fn rt_gc_log_inc(p: ?*anyopaque) void {
    logInc(p);
}
pub export fn rt_gc_log_dec(p: ?*anyopaque) void {
    logDec(p);
}
pub export fn rt_gc_write_barrier(o: ?*anyopaque) void {
    writeBarrier(o);
}
/// Nothing reads freed memory concurrently here: free now.
pub export fn rt_gc_free_later(p: ?*anyopaque) void {
    host.free(p);
}

// For tests/native/compact.zig.
pub fn debugRefCount(p: *anyopaque) u32 {
    return count(p).*;
}
pub fn debugColor(p: *anyopaque) u32 {
    return flags(p).* & COLOR;
}
pub fn debugIsBuffered(p: *anyopaque) bool {
    return flags(p).* & BUF != 0;
}
pub fn debugBuffered() u32 {
    return len;
}
pub fn debugThreshold() u32 {
    return threshold;
}
pub fn debugCapacity() u32 {
    return cap;
}
