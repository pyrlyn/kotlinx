//! Epoch collector: deferred RC + concurrent cycle collection.
//! Port of `epoch/gc.c` (Bacon & Rajan concurrent trial deletion). Parallel
//! MarkGray/Scan helpers and early-validation are not ported; the collector
//! runs on one thread (equivalent to KX_GC_THREADS=1). The incremental major
//! (KX_GC_INC_MAJOR, default 1) and side minors (KX_GC_SIDE_MINOR, default 1)
//! follow gc.c (im_begin / im_step / side_minor). Semantics of logs, CRC trial deletion, Sigma/Delta,
//! generations, return-to-mutator and exit drain match the C.
const std = @import("std");
const builtin = @import("builtin");
const kx = @import("kxgc");
const host = @import("host");
const obj = @import("common/object.zig");

pub const name = "epoch";
pub const caps = .{ .cycles = true, .precise = true, .asan = true, .deferred = true };

const PAGE_SHIFT = 16;
const PAGE: usize = 1 << PAGE_SHIFT;
const PAGE_BITS_LOG = 31;
const CLASSES = 32;
const LOG_CAP = 32768;
const LOG_MIN = 2048;
const LOG_PER_ROOT = 4;
const LOG_FLOOR_DEFAULT = 512;
const Q_ADAPT_START = 4;
const Q_ADAPT_MIN = 2;
const Q_ADAPT_MAX = 16;
const Q_ADAPT_COOL = 16; // hand-offs between two doublings
const Q_ADAPT_WINDOW = 64;
const BOUNDARY_KEEP_DEFAULT: i64 = 0;
const FREE_BUDGET_MIN_NS: i64 = 200_000;
const C_MAX_THRESHOLD = 1 << 18;
const MAJOR_RATIO: i64 = 4;
const OP_INC: usize = 0;
const OP_DEC: usize = 1;
const OP_MOD: usize = 2;
const OP_ROOT: usize = 3;

const DIRTY_BIT: u64 = 1 << 35;
const CAND_BIT: u64 = 1 << 36;
const DEAD_BIT: u64 = 1 << 37;
const ZOMBIE_BIT: u64 = 1 << 38;
const STABLE_BIT: u64 = 1 << 39;
const CRC_SHIFT: u6 = 40;
const CRC_MAX: u64 = 0x7fffff;
const CRC_MASK: u64 = CRC_MAX << CRC_SHIFT;
/// Visited by the in-progress incremental major (its memory is held until it ends).
const SEEN_BIT: u64 = 1 << 63;
const MAJOR_STEP: i64 = 2048; // visits per step when KX_GC_MAJOR_STEP_US=0
const MAJOR_STEP_US: i64 = 1000; // default: steps of 512 visits until 1 ms per log has passed
const SLICE_ROOTS: usize = 32768; // KX_GC_SLICE_ROOTS; 0 = unbounded (side minors only here)

var gc_st = kx.Stats{};
var page_bits: ?[*]std.atomic.Value(u64) = null;

fn regAdd(addr: usize) void {
    const pg = addr >> PAGE_SHIFT;
    if (pg >> PAGE_BITS_LOG != 0) host.rt_panic("epoch GC: heap address outside the page bitmap");
    const bits = page_bits.?;
    _ = bits[pg >> 6].fetchOr(@as(u64, 1) << @intCast(pg & 63), .release);
}

fn isTraced(p: ?*anyopaque) bool {
    const q = p orelse return false;
    const pg = @intFromPtr(q) >> PAGE_SHIFT;
    if (pg >> PAGE_BITS_LOG != 0 or page_bits == null) return false;
    return (page_bits.?[pg >> 6].load(.acquire) >> @intCast(pg & 63)) & 1 != 0;
}

const libc_mm = struct {
    extern "c" fn mmap(addr: ?*anyopaque, len: usize, prot: c_int, flags: c_int, fd: c_int, off: isize) *anyopaque;
    extern "c" fn munmap(addr: *anyopaque, len: usize) c_int;
    extern "c" fn madvise(addr: *anyopaque, len: usize, advice: c_int) c_int;
};

fn mapAnon(bytes: usize, noreserve: bool) [*]u8 {
    const prot: c_int = 1 | 2; // PROT_READ | PROT_WRITE
    var flags: c_int = if (comptime builtin.os.tag == .macos)
        0x0002 | 0x1000 // MAP_PRIVATE | MAP_ANON
    else
        0x0002 | 0x0020; // MAP_PRIVATE | MAP_ANONYMOUS
    if (noreserve and comptime builtin.os.tag == .linux) flags |= 0x4000; // MAP_NORESERVE
    const p = libc_mm.mmap(null, bytes, prot, flags, -1, 0);
    if (p == std.c.MAP_FAILED) host.oom();
    return @ptrCast(p);
}

fn ensureBits() void {
    if (page_bits != null) return;
    const bytes = (@as(usize, 1) << PAGE_BITS_LOG) / 8;
    page_bits = @ptrCast(@alignCast(mapAnon(bytes, true)));
}

const SLAB_CHUNK: usize = 2 << 20;
const SLAB_DIR_TOP: usize = 1 << (PAGE_BITS_LOG - 16);
const SLAB_RESIDENT_KEEP: i64 = 16;

const Slab = extern struct {
    next: ?*Slab = null,
    prev: ?*Slab = null,
    free: ?*anyopaque = null,
    base: [*]u8 = undefined,
    bump: [*]u8 = undefined,
    live: u32 = 0,
    cls: u16 = 0,
    in_partial: u8 = 0,
    resident: u8 = 1,
};

var slab_chunk: ?[*]u8 = null;
var slab_chunk_end: [*]u8 = undefined;
var slab_dir: [SLAB_DIR_TOP]?[*]?*Slab = @splat(null);
var slab_cur: [CLASSES + 1]?*Slab = @splat(null);
var slab_partial: [CLASSES + 1]?*Slab = @splat(null);
var slab_pool: ?*Slab = null;
var slab_pending = obj.PtrStack{};
var slab_release: i32 = -1;
var slab_keep: i64 = -1;
var slab_pool_resident: i64 = 0;

fn madvAdvice() c_int {
    if (comptime builtin.os.tag == .macos) return std.c.MADV.FREE_REUSABLE;
    return 4; // MADV_DONTNEED
}

fn madv(from: *anyopaque, len: usize) void {
    if (len == 0) return;
    _ = libc_mm.madvise(from, len, madvAdvice());
}

fn pageAlloc(bytes: usize) *anyopaque {
    ensureBits();
    var p: *anyopaque = undefined;
    if (bytes == PAGE) {
        if (slab_chunk == null or slab_chunk.? == slab_chunk_end) {
            const want = SLAB_CHUNK + PAGE;
            const mapped = mapAnon(want, false);
            const raw_addr = @intFromPtr(mapped);
            const aligned = (raw_addr + PAGE - 1) & ~@as(usize, PAGE - 1);
            if (aligned > raw_addr) _ = libc_mm.munmap(@ptrFromInt(raw_addr), aligned - raw_addr);
            if (aligned + SLAB_CHUNK < raw_addr + want) {
                _ = libc_mm.munmap(@ptrFromInt(aligned + SLAB_CHUNK), raw_addr + want - (aligned + SLAB_CHUNK));
            }
            slab_chunk = @ptrFromInt(aligned);
            slab_chunk_end = @ptrFromInt(aligned + SLAB_CHUNK);
        }
        p = @ptrCast(slab_chunk.?);
        slab_chunk.? += PAGE;
    } else {
        if (std.c.posix_memalign(@ptrCast(&p), PAGE, bytes) != 0) host.oom();
    }
    var o: usize = 0;
    while (o < bytes) : (o += PAGE) regAdd(@intFromPtr(p) + o);
    return p;
}

inline fn slabOf(p: *anyopaque) ?*Slab {
    const pg = @intFromPtr(p) >> PAGE_SHIFT;
    const d = slab_dir[pg >> 16] orelse return null;
    return d[pg & 0xffff];
}

fn slabNew() *Slab {
    const base: [*]u8 = @ptrCast(pageAlloc(PAGE));
    const pg = @intFromPtr(base) >> PAGE_SHIFT;
    var d = slab_dir[pg >> 16];
    if (d == null) {
        const raw = host.calloc((1 << 16) * @sizeOf(?*Slab));
        d = @ptrCast(@alignCast(raw));
        slab_dir[pg >> 16] = d;
    }
    const s: *Slab = @ptrCast(@alignCast(host.calloc(@sizeOf(Slab))));
    s.base = base;
    s.resident = 1;
    d.?[pg & 0xffff] = s;
    return s;
}

fn partialUnlink(s: *Slab, cls: usize) void {
    if (s.prev) |prev| prev.next = s.next else slab_partial[cls] = s.next;
    if (s.next) |next| next.prev = s.prev;
    s.next = null;
    s.prev = null;
    s.in_partial = 0;
}

// C: `__attribute__((noinline)) void *slab_refill` — keep the slab_alloc hot path small.
noinline fn slabRefill(cls: usize) *anyopaque {
    var s = slab_partial[cls];
    if (s) |taken| {
        partialUnlink(taken, cls);
    } else if (slab_pool) |pooled| {
        slab_pool = pooled.next;
        pooled.next = null;
        if (pooled.resident != 0) slab_pool_resident -= 1;
        pooled.resident = 1;
        pooled.free = null;
        pooled.bump = pooled.base;
        pooled.live = 0;
        s = pooled;
    } else {
        const fresh = slabNew();
        fresh.bump = fresh.base;
        s = fresh;
    }
    const slab = s.?;
    slab.cls = @intCast(cls);
    slab_cur[cls] = slab;
    var p: *anyopaque = undefined;
    if (slab.free) |free_cell| {
        p = free_cell;
        const next: *?*anyopaque = @ptrCast(@alignCast(free_cell));
        slab.free = next.*;
    } else {
        p = @ptrCast(slab.bump);
        slab.bump += cls << 3;
    }
    slab.live += 1;
    return p;
}

inline fn slabAlloc(cls: usize) *anyopaque {
    // C slab_alloc: likely current slab, free-list then bump, else noinline refill.
    const s = slab_cur[cls];
    if (s != null) {
        @branchHint(.likely);
        const slab = s.?;
        if (slab.free) |free_cell| {
            const next: *?*anyopaque = @ptrCast(@alignCast(free_cell));
            slab.free = next.*;
            slab.live += 1;
            return free_cell;
        }
        const next_bump = slab.bump + (cls << 3);
        if (@intFromPtr(next_bump) <= @intFromPtr(slab.base) + PAGE) {
            const p: *anyopaque = @ptrCast(slab.bump);
            slab.bump = next_bump;
            slab.live += 1;
            return p;
        }
    }
    return slabRefill(cls);
}

inline fn slabFree(p: *anyopaque, cls: usize) void {
    // C slab_free does not null-check slab_of: a traced cell always has a slab.
    const s = slabOf(p).?;
    const next: *?*anyopaque = @ptrCast(@alignCast(p));
    next.* = s.free;
    s.free = p;
    if (s == slab_cur[cls]) {
        s.live -= 1;
        return;
    }
    s.live -= 1;
    if (s.live == 0) {
        if (s.in_partial != 0) partialUnlink(s, cls);
        slab_pending.push(s);
    } else if (s.in_partial == 0) {
        s.in_partial = 1;
        s.prev = null;
        s.next = slab_partial[cls];
        if (s.next) |n| n.prev = s;
        slab_partial[cls] = s;
    }
}

fn slabCmp(_: void, a: ?*anyopaque, b: ?*anyopaque) bool {
    const sa: *Slab = @ptrCast(@alignCast(a.?));
    const sb: *Slab = @ptrCast(@alignCast(b.?));
    return @intFromPtr(sa.base) < @intFromPtr(sb.base);
}

fn slabFlush() void {
    if (slab_pending.len == 0) return;
    if (slab_keep < 0) {
        slab_keep = host.getenv_i64("KX_GC_SLAB_KEEP", SLAB_RESIDENT_KEEP);
        if (slab_keep < 0) slab_keep = 0;
    }
    var n: usize = 0;
    var i: usize = 0;
    while (i < slab_pending.len) : (i += 1) {
        const s: *Slab = @ptrCast(@alignCast(slab_pending.at(i).?));
        if (s.live != 0 or s == slab_cur[s.cls]) continue;
        s.next = slab_pool;
        slab_pool = s;
        if (slab_pool_resident < slab_keep) {
            slab_pool_resident += 1;
            continue;
        }
        s.resident = 0;
        slab_pending.set(n, s);
        n += 1;
    }
    if (n > 1) std.mem.sort(?*anyopaque, slab_pending.items[0..n], {}, slabCmp);
    i = 0;
    while (i < n) {
        var j = i + 1;
        while (j < n) {
            const prev: *Slab = @ptrCast(@alignCast(slab_pending.at(j - 1).?));
            const nxt: *Slab = @ptrCast(@alignCast(slab_pending.at(j).?));
            if (@intFromPtr(nxt.base) != @intFromPtr(prev.base) + PAGE) break;
            j += 1;
        }
        const from: *Slab = @ptrCast(@alignCast(slab_pending.at(i).?));
        madv(@ptrCast(from.base), (j - i) * PAGE);
        i = j;
    }
    slab_pending.len = 0;
}

const Big = extern struct { next: ?*Big, size: usize };
var free_list: [CLASSES + 1]?*anyopaque = @splat(null);
var bump: ?[*]u8 = null;
var bump_end: [*]u8 = undefined;
var big_free: ?*Big = null;
var t_allocs: std.atomic.Value(i64) = std.atomic.Value(i64).init(0);
var t_frees: std.atomic.Value(i64) = std.atomic.Value(i64).init(0);

pub export fn rt_alloc(size: i64) ?*anyopaque {
    return host.malloc(@intCast(size));
}
pub export fn rt_free(p: ?*anyopaque, size: i64) void {
    _ = size;
    host.free(p);
}
pub export fn rt_gc_mem_alloc(n: usize) ?*anyopaque {
    if (n == 0) return host.malloc(0);
    return host.malloc(n);
}
pub export fn rt_gc_mem_calloc(n: usize) ?*anyopaque {
    return host.calloc(n);
}
pub export fn rt_gc_mem_realloc(p: ?*anyopaque, n: usize) ?*anyopaque {
    return host.realloc(p, n);
}
pub export fn rt_gc_mem_free(p: ?*anyopaque) void {
    host.free(p);
}

var big_madvise: i32 = -1;
noinline fn allocBig(size: i64) *anyopaque {
    const bytes = (@as(usize, @intCast(size)) + PAGE - 1) & ~@as(usize, PAGE - 1);
    var b = big_free;
    var prev: *?*Big = &big_free;
    while (b) |blk| {
        if (blk.size == bytes) {
            prev.* = blk.next;
            return @ptrCast(blk);
        }
        prev = &blk.next;
        b = blk.next;
    }
    return pageAlloc(bytes);
}

noinline fn initSlabRelease() void {
    const e = std.c.getenv("KX_GC_SLAB_RELEASE");
    slab_release = if (e != null and e.?[0] == '0') 0 else 1;
}

noinline fn allocNoSlab(cls: usize) *anyopaque {
    if (free_list[cls]) |p| {
        const next: *?*anyopaque = @ptrCast(@alignCast(p));
        free_list[cls] = next.*;
        return p;
    }
    const need = cls << 3;
    if (bump == null or @intFromPtr(bump.?) + need > @intFromPtr(bump_end)) {
        const slab: [*]u8 = @ptrCast(pageAlloc(PAGE));
        bump = slab;
        bump_end = slab + PAGE;
    }
    const p: *anyopaque = @ptrCast(bump.?);
    bump.? += need;
    return p;
}

var dbg_madvised: i64 = 0;
fn bigRelease(p: *anyopaque, bytes: usize) void {
    if (big_madvise < 0) {
        const e = std.c.getenv("KX_GC_MADVISE");
        big_madvise = if (e != null and e.?[0] == '0') 0 else 1;
    }
    if (big_madvise == 0) return;
    const os_page = std.heap.pageSize();
    if (bytes < 2 * os_page) return;
    const from: [*]u8 = @ptrCast(p);
    const len = bytes - os_page;
    // C: if (madvise(...) == 0) dbg_madvised += len;
    if (libc_mm.madvise(@ptrCast(from + os_page), len, madvAdvice()) == 0) dbg_madvised += @intCast(len);
}

pub inline fn allocTrace(size: i64) ?*anyopaque {
    // Keep this body tiny so Darwin no-LTO inlines it into newObj / op_alloc
    // the way C -flto inlines rt_alloc_trace → slab_alloc. Cold paths are noinline.
    t_allocs.store(t_allocs.load(.monotonic) + 1, .monotonic);
    if (ret_pos < ret_local.len) freePaced();
    const cls = (@as(usize, @intCast(size)) + 7) >> 3;
    if (cls > CLASSES) {
        @branchHint(.unlikely);
        return allocBig(size);
    }
    if (slab_release < 0) {
        @branchHint(.unlikely);
        initSlabRelease();
    }
    if (slab_release != 0) return slabAlloc(cls);
    return allocNoSlab(cls);
}

pub export fn rt_alloc_trace(size: i64) ?*anyopaque {
    return allocTrace(size);
}

pub inline fn freeTrace(p: ?*anyopaque, size: i64) void {
    t_frees.store(t_frees.load(.monotonic) + 1, .monotonic);
    const q = p orelse return;
    const cls = (@as(usize, @intCast(size)) + 7) >> 3;
    if (cls > CLASSES) {
        const bytes = (@as(usize, @intCast(size)) + PAGE - 1) & ~@as(usize, PAGE - 1);
        const b: *Big = @ptrCast(@alignCast(q));
        b.size = bytes;
        b.next = big_free;
        big_free = b;
        bigRelease(q, bytes);
        return;
    }
    if (slab_release > 0) {
        slabFree(q, cls);
        return;
    }
    const next: *?*anyopaque = @ptrCast(@alignCast(q));
    next.* = free_list[cls];
    free_list[cls] = q;
}

pub export fn rt_free_trace(p: ?*anyopaque, size: i64) void {
    freeTrace(p, size);
}

pub export fn rt_cc_possible_root(p: ?*anyopaque) void {
    _ = p;
    host.rt_panic("epoch GC: rt_cc_possible_root");
}
pub export fn rt_cc_dead(p: ?*anyopaque) void {
    _ = p;
    host.rt_panic("epoch GC: rt_cc_dead");
}

const Log = struct {
    next: ?*Log = null,
    n: usize = 0,
    seq: u64 = 0,
    frees: obj.PtrStack = .{},
    a: [LOG_CAP]usize = undefined,
};

var log_cap: std.atomic.Value(usize) = std.atomic.Value(usize).init(512);
var log_cap_fixed: bool = false;
var log_boost: std.atomic.Value(usize) = std.atomic.Value(usize).init(1);
var c_threshold: usize = LOG_MIN / LOG_PER_ROOT;
var c_min_threshold: usize = LOG_MIN / LOG_PER_ROOT;
var log_floor: usize = LOG_FLOOR_DEFAULT;
var c_pure: bool = false;
var max_queued: usize = 0;
var q_fixed: bool = false;
var mu: std.c.pthread_mutex_t = std.c.PTHREAD_MUTEX_INITIALIZER;
var cv_work: std.c.pthread_cond_t = std.c.PTHREAD_COND_INITIALIZER;
var cv_done: std.c.pthread_cond_t = std.c.PTHREAD_COND_INITIALIZER;

fn lock() void {
    _ = std.c.pthread_mutex_lock(&mu);
}
fn unlock() void {
    _ = std.c.pthread_mutex_unlock(&mu);
}
fn waitWork() void {
    _ = std.c.pthread_cond_wait(&cv_work, &mu);
}
fn waitDone() void {
    _ = std.c.pthread_cond_wait(&cv_done, &mu);
}
fn signalWork() void {
    _ = std.c.pthread_cond_signal(&cv_work);
}
fn broadcastDone() void {
    _ = std.c.pthread_cond_broadcast(&cv_done);
}
var cur: ?*Log = null;
var q_head: ?*Log = null;
var q_tail: ?*Log = null;
var log_pool: ?*Log = null;
var pool_len: usize = 0;
var dbg_log_allocs: i64 = 0;
var q_len: usize = 0;
var handed: u64 = 0;
var applied: u64 = 0;
var m_applied: u64 = 0;
var ret_shared = obj.PtrStack{};
var ret_local = obj.PtrStack{};
var ret_pos: usize = 0;
var full_req: bool = false;
var full_done: u64 = 0;
var shutting: bool = false;
var coll_started: bool = false;
var in_boundary: bool = false;
var defer_live: bool = true;
var boundary_keep: i64 = -1; // KX_GC_BOUNDARY_KEEP; -1 = unread (default 0)
var free_budget_ns: i64 = -1;
var q_target_ns: i64 = 0;
var q_cool: i32 = 0;
var q_win: i32 = 0;
var q_win_bad: i32 = 0;
var dbg_q_max: usize = 0;
var dbg_paced: i64 = 0;
var dbg_bfreed: i64 = 0;

fn logNew() *Log {
    if (log_pool) |l| {
        log_pool = l.next;
        if (pool_len > 0) pool_len -= 1;
        l.n = 0;
        l.next = null;
        l.frees.len = 0;
        return l;
    }
    // C malloc + n/next/frees only. Do not `l.* = .{}` — that writes the 256 KiB
    // `a[]` on every new log (mutator-visible when field_store fills a log).
    dbg_log_allocs += 1;
    const l: *Log = @ptrCast(@alignCast(host.malloc(@sizeOf(Log))));
    l.next = null;
    l.n = 0;
    l.seq = 0;
    l.frees = .{};
    return l;
}

fn logCapFollow() void {
    if (log_cap_fixed) return;
    const boost = log_boost.load(.monotonic);
    var cap = c_threshold * LOG_PER_ROOT;
    const b = LOG_MIN * boost;
    if (cap < b) cap = b;
    if (cap > LOG_CAP) cap = LOG_CAP;
    if (c_pure and c_threshold <= c_min_threshold and boost == 1 and log_floor < cap) cap = log_floor;
    log_cap.store(cap, .monotonic);
}

fn handoff() void {
    lock();
    if (!coll_started) {
        coll_started = true;
        const t = std.Thread.spawn(.{}, collectorMain, .{}) catch {
            host.rt_panic("epoch GC: cannot start the collector thread");
        };
        t.detach();
    }
    if (cur) |l| {
        if (l.n != 0 or l.frees.len != 0) {
            handed += 1;
            l.seq = handed;
            if (q_tail) |tail| tail.next = l else q_head = l;
            q_tail = l;
            q_len += 1;
            cur = null;
            signalWork();
        }
    }
    if (max_queued == 0) {
        const e = std.c.getenv("KX_GC_MAX_QUEUED");
        if (e != null and e.?[0] != 0) {
            max_queued = @intCast(host.getenv_i64("KX_GC_MAX_QUEUED", Q_ADAPT_START));
            q_fixed = true;
        } else max_queued = Q_ADAPT_START;
        // KX_GC_TARGET_PAUSE_US, default 3 ms (C handoff)
        q_target_ns = host.getenv_i64("KX_GC_TARGET_PAUSE_US", 3000) * 1000;
    }
    var d: i64 = 0;
    if (q_len > max_queued) {
        const w0 = host.rt_nanos();
        while (q_len > max_queued) waitDone();
        d = host.rt_nanos() - w0;
    }
    // Adaptive queue depth (gc.c handoff): back-pressure over the pause target doubles
    // max_queued up to Q_ADAPT_MAX; a calm window halves it down to Q_ADAPT_MIN. At the
    // max, longer logs (log_boost) absorb spikes instead of growing the queue unboundedly.
    if (!q_fixed) {
        if (q_cool > 0) q_cool -= 1;
        if (d > q_target_ns and q_cool == 0) {
            if (max_queued >= Q_ADAPT_MAX and !log_cap_fixed and !(c_pure) and LOG_MIN * log_boost.load(.monotonic) < LOG_CAP) {
                const b = log_boost.load(.monotonic);
                log_boost.store(b + b, .monotonic);
                logCapFollow();
            }
            if (max_queued < Q_ADAPT_MAX) max_queued *= 2;
            if (max_queued > Q_ADAPT_MAX) max_queued = Q_ADAPT_MAX;
            q_win = 0;
            q_win_bad = 0;
            q_cool = Q_ADAPT_COOL;
        } else if (d > q_target_ns) {
            q_win_bad = 1;
        } else {
            if (d > @divTrunc(q_target_ns, 4) or q_len * 2 > max_queued) q_win_bad = 1;
            q_win += 1;
            if (q_win >= Q_ADAPT_WINDOW and q_win_bad == 0 and log_boost.load(.monotonic) > 1) {
                log_boost.store(log_boost.load(.monotonic) / 2, .monotonic);
                logCapFollow();
                q_win = 0;
            } else if (q_win >= Q_ADAPT_WINDOW) {
                if (q_win_bad == 0 and max_queued > Q_ADAPT_MIN) {
                    const half = max_queued / 2;
                    max_queued = if (half > Q_ADAPT_MIN) half else Q_ADAPT_MIN;
                }
                q_win = 0;
                q_win_bad = 0;
            }
        }
        if (max_queued > dbg_q_max) dbg_q_max = max_queued;
    }
    obj.PtrStack.moveFrom(&ret_local, &ret_shared);
    m_applied = applied;
    if (cur == null) cur = logNew();
    unlock();
}

var c_roots = obj.PtrStack{};
var c_work = obj.PtrStack{};
var c_ret = obj.PtrStack{};
var c_deferred = obj.PtrStack{};
var c_live_def = obj.PtrStack{};
var c_dying = obj.PtrStack{};
var c_stable_n: i64 = 0;
var c_releasing: bool = false;
var c_zombies: usize = 0;
var defer_flush: i64 = 8;
var next_set_id: u64 = 1;
var c_major: bool = false;

const Batch = struct {
    next: ?*Batch = null,
    valid_after: u64 = 0,
    first_id: u64 = 0,
    nsets: usize = 0,
    ends: []usize = &.{},
    members: []?*anyopaque = &.{},
};
var cands: ?*Batch = null;
var cands_tail: ?*Batch = null;
var cw_m = obj.PtrStack{};
var cw_ends = obj.PtrStack{};
var cw_first_id: u64 = 0;
var cw_id: u64 = 0;

fn has(p: *anyopaque, bit: u64) bool {
    return (obj.header(p).* & bit) != 0;
}
fn setBit(p: *anyopaque, bit: u64) void {
    obj.header(p).* |= bit;
}
fn clrBit(p: *anyopaque, bit: u64) void {
    obj.header(p).* &= ~bit;
}
fn crcOf(p: *anyopaque) u64 {
    return (obj.header(p).* >> CRC_SHIFT) & CRC_MAX;
}
fn setCrc(p: *anyopaque, v: u64) void {
    obj.header(p).* = (obj.header(p).* & ~CRC_MASK) | ((v & CRC_MAX) << CRC_SHIFT);
}

fn touchKeep(p: *anyopaque) void {
    obj.header(p).* |= DIRTY_BIT;
}
fn touch(p: *anyopaque) void {
    const h = obj.header(p).*;
    if (h & STABLE_BIT != 0) c_stable_n -= 1;
    obj.header(p).* = (h | DIRTY_BIT) & ~STABLE_BIT;
}
fn promote(p: *anyopaque) void {
    if (!has(p, STABLE_BIT)) {
        setBit(p, STABLE_BIT);
        c_stable_n += 1;
    }
}

// Incremental major state (gc.c: c_im, im_seen, c_hold, sm_retry, im_*).
var c_im: bool = false;
var im_seen = obj.PtrStack{};
var c_hold = obj.PtrStack{};
var sm_retry = obj.PtrStack{};
var im_state: u8 = 0; // 0 none, 1 MarkGray, 2 Scan
var im_roots = obj.PtrStack{};
// The major's own work/black stacks (gc.c workers[0].work/black): side minors and
// CollectWhite use c_work while a major is between steps.
var im_work = obj.PtrStack{};
var im_black = obj.PtrStack{};
var im_k: usize = 0;
var im_cur: usize = 0; // lean-stacks cursor into im_roots
var im_step_budget: i64 = -1;
var im_step_ns: i64 = -1;
var im_steps: i64 = 0;
var incremental_major: i8 = -1; // KX_GC_INC_MAJOR (default 1)
var side_minors: bool = true; // KX_GC_SIDE_MINOR (default 1)
var slice_roots: usize = SLICE_ROOTS;
var major_ratio: i64 = MAJOR_RATIO; // KX_GC_MAJOR_RATIO (tests: majors sooner)
var dbg_held: i64 = 0;
var dbg_held_max: i64 = 0;
var dbg_side_minors: i64 = 0;
var dbg_side_visits: i64 = 0;
var lean_stacks_v: i8 = -1;
/// Test hook (debugRequestIncMajor): start an incremental major at the next applied log.
var dbg_force_im = std.atomic.Value(bool).init(false);

fn leanStacks() bool {
    if (lean_stacks_v < 0) {
        const e = std.c.getenv("KX_GC_LEAN_STACKS");
        lean_stacks_v = if (e != null and e.?[0] == '0') 0 else 1;
    }
    return lean_stacks_v != 0;
}

inline fn travIt(x: *anyopaque, f: kx.VisitFn) void {
    if (obj.vtab(x).trav) |t| t(x, f);
}

fn imDead(x: *anyopaque) bool {
    return obj.refCount(x) == 0 or has(x, DEAD_BIT);
}

fn retPush(p: *anyopaque) void {
    if (c_im and has(p, SEEN_BIT)) {
        setBit(p, DEAD_BIT);
        c_hold.push(p);
        dbg_held += 1;
    } else c_ret.push(p);
}

/// Scan can reach an object MarkGray did not; its memory must be held like every visited object.
fn imNote(t: *anyopaque) void {
    if (c_im and !has(t, SEEN_BIT)) {
        setBit(t, SEEN_BIT);
        im_seen.push(t);
    }
}

fn releaseDeadChild(t: ?*anyopaque) callconv(.c) void {
    if (t) |p| if (isTraced(p)) decApply(p);
}

fn releaseChildren(p: *anyopaque) void {
    if (has(p, STABLE_BIT)) {
        clrBit(p, STABLE_BIT);
        c_stable_n -= 1;
    }
    clrBit(p, DEAD_BIT);
    obj.setColor(p, obj.BLACK);
    c_dying.push(p);
    if (c_releasing) return;
    c_releasing = true;
    const pm = host.rt_trav_mode;
    host.rt_trav_mode = kx.TRAV_RELEASE;
    while (c_dying.len != 0) {
        const x = c_dying.pop().?;
        if (obj.vtab(x).trav) |trav| trav(x, releaseDeadChild);
    }
    host.rt_trav_mode = pm;
    c_releasing = false;
}

fn releaseDead(p: *anyopaque) void {
    retPush(p);
    releaseChildren(p);
}
fn releaseZombie(p: *anyopaque) void {
    c_zombies += 1;
    setBit(p, ZOMBIE_BIT);
    releaseChildren(p);
}

fn possibleRoot(p: *anyopaque) void {
    obj.setColor(p, obj.PURPLE);
    if (!has(p, kx.BUF_BIT)) {
        setBit(p, kx.BUF_BIT);
        c_roots.push(p);
    }
}

fn decApply(p: *anyopaque) void {
    const h = obj.header(p).*;
    if (h & kx.CNT_MASK == 0) host.rt_panic("epoch GC: reference count underflow");
    if ((h & STABLE_BIT) != 0 and (h & kx.CNT_MASK) > 1 and defer_live) {
        obj.header(p).* = (h - 1) | DIRTY_BIT;
        if ((h & (kx.BUF_BIT | CAND_BIT)) == 0) {
            obj.setColor(p, obj.PURPLE);
            setBit(p, kx.BUF_BIT);
            c_live_def.push(p);
        }
        return;
    }
    obj.header(p).* = h - 1;
    touch(p);
    if (obj.refCount(p) == 0) {
        if (has(p, CAND_BIT)) setBit(p, DEAD_BIT) else if (has(p, kx.BUF_BIT)) releaseZombie(p) else releaseDead(p);
    } else possibleRoot(p);
}

fn hintApply(p: *anyopaque) void {
    const h = obj.header(p).*;
    if (h & kx.CNT_MASK == 0) return;
    if ((h & STABLE_BIT) != 0 and defer_live) {
        obj.header(p).* = h | DIRTY_BIT;
        if ((h & (kx.BUF_BIT | CAND_BIT)) == 0) {
            obj.setColor(p, obj.PURPLE);
            setBit(p, kx.BUF_BIT);
            c_live_def.push(p);
        }
        return;
    }
    touch(p);
    possibleRoot(p);
}

fn applyLog(l: *Log) void {
    var nd: usize = 0;
    var i: usize = 0;
    while (i < l.n) : (i += 1) {
        const e = l.a[i];
        const p: *anyopaque = @ptrFromInt(e & ~@as(usize, 3));
        if ((e & 3) == OP_INC) {
            obj.header(p).* = (obj.header(p).* + 1) | DIRTY_BIT;
        } else if ((e & 3) == OP_MOD) {
            touchKeep(p);
        } else {
            l.a[nd] = e;
            nd += 1;
        }
    }
    i = 0;
    while (i < nd) : (i += 1) {
        const e = l.a[i];
        const p: *anyopaque = @ptrFromInt(e & ~@as(usize, 3));
        if ((e & 3) == OP_DEC) decApply(p) else hintApply(p);
    }
}

fn mgChild(t: ?*anyopaque) callconv(.c) void {
    const p = t orelse return;
    if (!isTraced(p)) return;
    var h = obj.header(p).*;
    if (h & (CAND_BIT | DEAD_BIT) != 0) return;
    if (!c_major and (h & STABLE_BIT) != 0) {
        c_hit_stable = true;
        return;
    }
    obj.header(p).* = h + (@as(u64, 1) << CRC_SHIFT);
    h = obj.header(p).*;
    if (((h >> kx.COLOR_SHIFT) & 3) != obj.GRAY) {
        // Clear Delta only on the first visit (CRC 1): an incremental major may see an
        // object re-colored by a log applied between its steps and must keep that touch.
        obj.setColor(p, obj.GRAY);
        if ((h >> CRC_SHIFT) & CRC_MAX == 1) clrBit(p, DIRTY_BIT);
        if (c_im) {
            setBit(p, SEEN_BIT);
            if (h & SEEN_BIT == 0) im_seen.push(p);
            im_work.push(p);
        } else {
            c_marked.push(p);
            c_work.push(p);
        }
    }
}

fn sbChild(t: ?*anyopaque) callconv(.c) void {
    const p = t orelse return;
    if (!isTraced(p)) return;
    const h = obj.header(p).*;
    if (h & (CAND_BIT | DEAD_BIT) != 0) return;
    if (((h >> kx.COLOR_SHIFT) & 3) != obj.BLACK) {
        obj.setColor(p, obj.BLACK);
        setCrc(p, 0);
        imNote(p);
        promote(p);
        if (c_im) im_black.push(p) else c_black.push(p);
    }
}

var c_black = obj.PtrStack{};
var c_hit_stable: bool = false;
var c_marked = obj.PtrStack{};

fn scanOne(x: *anyopaque) void {
    const h = obj.header(x).*;
    if (((h >> kx.COLOR_SHIFT) & 3) != obj.GRAY) return;
    const ext: i64 = @as(i64, @intCast(h & kx.CNT_MASK)) - @as(i64, @intCast((h >> CRC_SHIFT) & CRC_MAX));
    if (ext > 0) {
        obj.setColor(x, obj.BLACK);
        setCrc(x, 0);
        promote(x);
        c_black.push(x);
        while (c_black.len != 0) {
            const y = c_black.pop().?;
            if (obj.vtab(y).trav) |trav| trav(y, sbChild);
        }
    } else {
        obj.setColor(x, obj.WHITE);
        if (obj.vtab(x).trav) |trav| trav(x, scanChild);
    }
}

fn scanChild(t: ?*anyopaque) callconv(.c) void {
    if (t) |p| if (isTraced(p) and !has(p, CAND_BIT)) {
        imNote(p);
        if (c_im) im_work.push(p) else c_work.push(p);
    };
}

fn cwChild(t: ?*anyopaque) callconv(.c) void {
    const p = t orelse return;
    if (!isTraced(p) or has(p, CAND_BIT)) return;
    if (obj.color(p) == obj.WHITE and obj.refCount(p) > 0 and !has(p, DEAD_BIT)) {
        if (has(p, STABLE_BIT)) {
            clrBit(p, STABLE_BIT);
            c_stable_n -= 1;
        }
        setBit(p, CAND_BIT);
        setCrc(p, cw_id & CRC_MAX);
        cw_m.push(p);
        c_work.push(p);
    }
}

fn collectWhite(s: *anyopaque) void {
    if (obj.color(s) != obj.WHITE or has(s, CAND_BIT | DEAD_BIT) or obj.refCount(s) == 0) return;
    if (cw_ends.len == 0) cw_first_id = next_set_id;
    cw_id = next_set_id;
    next_set_id += 1;
    if (has(s, STABLE_BIT)) {
        clrBit(s, STABLE_BIT);
        c_stable_n -= 1;
    }
    setBit(s, CAND_BIT);
    setCrc(s, cw_id & CRC_MAX);
    cw_m.push(s);
    c_work.push(s);
    while (c_work.len != 0) {
        const x = c_work.pop().?;
        if (obj.vtab(x).trav) |trav| trav(x, cwChild);
    }
    cw_ends.push(@ptrFromInt(cw_m.len));
}

var v_sid: u64 = 0;
var v_internal: i64 = 0;
fn inSet(t: *anyopaque) bool {
    return has(t, CAND_BIT) and crcOf(t) == v_sid;
}
fn countChild(t: ?*anyopaque) callconv(.c) void {
    const p = t orelse return;
    if (isTraced(p) and inSet(p)) v_internal += 1;
}
fn freeCycleChild(t: ?*anyopaque) callconv(.c) void {
    if (t) |p| if (isTraced(p) and !inSet(p)) decApply(p);
}

fn closeSets() void {
    var start: usize = 0;
    var s: usize = 0;
    while (s < cw_ends.len) : (s += 1) {
        const end = @intFromPtr(cw_ends.at(s));
        var sum: i64 = 0;
        v_sid = (cw_first_id + s) & CRC_MAX;
        v_internal = 0;
        var i = start;
        while (i < end) : (i += 1) {
            const x = cw_m.at(i).?;
            sum += @intCast(obj.refCount(x));
            if (obj.vtab(x).trav) |trav| trav(x, countChild);
        }
        if (sum != v_internal) touchKeep(cw_m.at(start).?);
        start = end;
    }
    lock();
    const h = handed;
    unlock();
    if (cw_ends.len == 0) return;
    const n = cw_m.len;
    const k = cw_ends.len;
    const b: *Batch = @ptrCast(@alignCast(host.malloc(@sizeOf(Batch))));
    const mem: [*]?*anyopaque = @ptrCast(@alignCast(host.malloc(n * @sizeOf(?*anyopaque))));
    const ends: [*]usize = @ptrCast(@alignCast(host.malloc(k * @sizeOf(usize))));
    var i: usize = 0;
    while (i < n) : (i += 1) mem[i] = cw_m.at(i);
    i = 0;
    while (i < k) : (i += 1) ends[i] = @intFromPtr(cw_ends.at(i));
    b.* = .{ .valid_after = h, .first_id = cw_first_id, .nsets = k, .ends = ends[0..k], .members = mem[0..n] };
    if (cands_tail) |t| t.next = b else cands = b;
    cands_tail = b;
    cw_m.len = 0;
    cw_ends.len = 0;
}

fn validate(id: u64, members: []?*anyopaque) usize {
    var ok = true;
    v_sid = id & CRC_MAX;
    for (members) |m| {
        if (obj.header(m.?).* & (DIRTY_BIT | DEAD_BIT) != 0) {
            ok = false;
            break;
        }
    }
    if (ok) {
        const pm = host.rt_trav_mode;
        host.rt_trav_mode = kx.TRAV_RELEASE;
        for (members) |m| if (obj.vtab(m.?).trav) |trav| trav(m.?, freeCycleChild);
        host.rt_trav_mode = pm;
        for (members) |mm| {
            const x = mm.?;
            clrBit(x, CAND_BIT);
            obj.setColor(x, obj.BLACK);
            setCrc(x, 0);
            obj.header(x).* &= ~kx.CNT_MASK;
            if (has(x, kx.BUF_BIT)) setBit(x, ZOMBIE_BIT) else retPush(x);
        }
        return members.len;
    }
    for (members) |mm| {
        const x = mm.?;
        clrBit(x, CAND_BIT);
        obj.setColor(x, obj.BLACK);
        setCrc(x, 0);
        if (has(x, DEAD_BIT)) {
            if (has(x, kx.BUF_BIT)) releaseZombie(x) else releaseDead(x);
        } else if (obj.refCount(x) > 0) possibleRoot(x);
    }
    return 0;
}

fn validateReady(upto: u64, force: bool) usize {
    var freed: usize = 0;
    while (cands) |b| {
        if (!force and b.valid_after >= upto) break;
        cands = b.next;
        if (cands == null) cands_tail = null;
        var start: usize = 0;
        var i: usize = 0;
        while (i < b.nsets) : (i += 1) {
            freed += validate(b.first_id + i, b.members[start..b.ends[i]]);
            start = b.ends[i];
        }
        host.free(@ptrCast(b.members.ptr));
        host.free(@ptrCast(b.ends.ptr));
        host.free(@ptrCast(b));
    }
    return freed;
}

fn detectPrepare(major: bool) obj.PtrStack {
    c_major = major;
    if (major) obj.PtrStack.moveFrom(&c_deferred, &c_live_def);
    if (major) obj.PtrStack.moveFrom(&c_roots, &c_deferred);
    var roots = c_roots;
    c_roots = .{};
    var i: usize = 0;
    while (i < roots.len) : (i += 1) {
        const s = roots.at(i).?;
        clrBit(s, kx.BUF_BIT);
        if (has(s, ZOMBIE_BIT)) {
            clrBit(s, ZOMBIE_BIT);
            retPush(s);
            roots.set(i, null);
            continue;
        }
        if (has(s, DEAD_BIT) and !has(s, CAND_BIT)) {
            clrBit(s, DEAD_BIT);
            releaseDead(s);
            roots.set(i, null);
        }
    }
    var k: usize = 0;
    i = 0;
    while (i < roots.len) : (i += 1) {
        const s = roots.at(i) orelse continue;
        if (has(s, CAND_BIT | DEAD_BIT)) continue;
        if (obj.refCount(s) > 0) {
            obj.setColor(s, obj.GRAY);
            setCrc(s, 0);
            clrBit(s, DIRTY_BIT);
            roots.set(k, s);
            k += 1;
        }
    }
    roots.len = k;
    return roots;
}

fn settle(x: *anyopaque) void {
    if (has(x, CAND_BIT)) return;
    if (obj.color(x) == obj.GRAY or obj.color(x) == obj.WHITE) obj.setColor(x, obj.BLACK);
    setCrc(x, 0);
}

fn detect(major: bool) void {
    var roots = detectPrepare(major);
    c_hit_stable = false;
    var i: usize = 0;
    while (i < roots.len) : (i += 1) {
        const r = roots.at(i).?;
        c_work.push(r);
        c_marked.push(r);
    }
    while (c_work.len != 0) {
        const x = c_work.pop().?;
        if (obj.vtab(x).trav) |trav| trav(x, mgChild);
    }
    i = 0;
    while (i < roots.len) : (i += 1) c_work.push(roots.at(i).?);
    while (c_work.len != 0) scanOne(c_work.pop().?);
    detectFinish(major, &roots, roots.len, c_hit_stable);
    closeSets();
}

fn detectFinish(major: bool, roots: *obj.PtrStack, k: usize, hit_stable: bool) void {
    var i: usize = 0;
    while (i < k) : (i += 1) collectWhite(roots.at(i).?);
    i = 0;
    while (i < c_marked.len) : (i += 1) settle(c_marked.at(i).?);
    c_marked.len = 0;
    i = 0;
    while (i < im_seen.len) : (i += 1) settle(im_seen.at(i).?);
    if (c_im and leanStacks()) {
        i = 0;
        while (i < k) : (i += 1) {
            settle(roots.at(i).?);
            clrBit(roots.at(i).?, SEEN_BIT);
        }
    }
    if (!major and hit_stable) {
        i = 0;
        while (i < k) : (i += 1) {
            const s = roots.at(i).?;
            if (!has(s, CAND_BIT) and !has(s, kx.BUF_BIT)) {
                setBit(s, kx.BUF_BIT);
                c_deferred.push(s);
            }
        }
    }
    roots.deinit();
    publishReturns();
}

// Incremental major (gc.c im_begin / im_step): the old-generation MarkGray + Scan run in
// bounded steps between applied logs. Every visited object is SEEN; a SEEN object that
// dies is held (DEAD, c_hold) until the major ends. See gc.c for the full argument.
fn imBegin() void {
    im_roots = detectPrepare(true);
    im_k = im_roots.len;
    c_im = true;
    const lean = leanStacks();
    var i: usize = 0;
    while (i < im_k) : (i += 1) {
        setBit(im_roots.at(i).?, SEEN_BIT);
        if (!lean) im_seen.push(im_roots.at(i).?);
    }
    im_cur = 0;
    if (!lean) {
        i = 0;
        while (i < im_k) : (i += 1) im_work.push(im_roots.at(i).?);
    }
    im_state = 1;
}

/// One bounded step; returns true when the major finished.
fn imStep(budget: i64) bool {
    c_major = true;
    var n: i64 = 0;
    const lean = leanStacks();
    if (im_state == 1) {
        while (n < budget) {
            var x: *anyopaque = undefined;
            if (im_work.len != 0) x = im_work.pop().? else if (lean and im_cur < im_k) {
                x = im_roots.at(im_cur).?;
                im_cur += 1;
            } else break;
            if (imDead(x)) continue;
            travIt(x, mgChild);
            n += 1;
        }
        if (im_work.len != 0 or (lean and im_cur < im_k)) return false;
        if (!lean) {
            var i: usize = 0;
            while (i < im_k) : (i += 1) im_work.push(im_roots.at(i).?);
        }
        im_cur = 0;
        im_state = 2;
    }
    while (n < budget) {
        if (im_black.len != 0) {
            const y = im_black.pop().?;
            if (!imDead(y)) travIt(y, sbChild);
        } else if (im_work.len != 0 or (lean and im_cur < im_k)) {
            var x: *anyopaque = undefined;
            if (im_work.len != 0) x = im_work.pop().? else {
                x = im_roots.at(im_cur).?;
                im_cur += 1;
            }
            const h = obj.header(x).*;
            if (((h >> kx.COLOR_SHIFT) & 3) == obj.GRAY) {
                const ext: i64 = @as(i64, @intCast(h & kx.CNT_MASK)) - @as(i64, @intCast((h >> CRC_SHIFT) & CRC_MAX));
                if (ext > 0) {
                    obj.setColor(x, obj.BLACK);
                    setCrc(x, 0);
                    promote(x);
                    im_black.push(x);
                } else {
                    obj.setColor(x, obj.WHITE);
                    if (!imDead(x)) travIt(x, scanChild);
                }
            }
        } else break;
        n += 1;
    }
    im_steps += 1;
    if (im_work.len != 0 or im_black.len != 0 or (lean and im_cur < im_k)) return false;
    detectFinish(true, &im_roots, im_k, false);
    // detect_finish in C closes the final white sets, including an incremental major.
    // Without this the final candidates would wait for a later, possibly nonexistent, detection.
    closeSets();
    im_roots = .{};
    im_state = 0;
    c_im = false;
    var i: usize = 0;
    while (i < im_seen.len) : (i += 1) clrBit(im_seen.at(i).?, SEEN_BIT);
    im_seen.deinit();
    im_work.deinit();
    im_black.deinit();
    if (@as(i64, @intCast(c_hold.len)) > dbg_held_max) dbg_held_max = @intCast(c_hold.len);
    obj.PtrStack.moveFrom(&c_ret, &c_hold);
    obj.PtrStack.moveFrom(&c_roots, &sm_retry);
    return true;
}

// Side minor (gc.c side_minor): young-generation trial deletion while an incremental major
// is in progress, with private colors/counts in sm_tab; never writes a header color of a
// traced object, treats SEEN / stable / candidate / dead objects as opaque.
const SmEnt = struct { k: ?*anyopaque, crc: u32, col: u8 };
const SM_NONE: u8 = 0;
const SM_GRAY: u8 = 1;
const SM_WHITE: u8 = 2;
const SM_BLACK: u8 = 3;
var sm_tab: ?[*]SmEnt = null;
var sm_cap: usize = 0;
var sm_n: usize = 0;
var sm_work = obj.PtrStack{};
var sm_black = obj.PtrStack{};
var sm_roots = obj.PtrStack{};
var sm_hit_seen: bool = false;
var sm_hit_stable: bool = false;

inline fn smHash(p: *anyopaque) usize {
    const x: u64 = (@as(u64, @intFromPtr(p)) >> 3) *% 0x9E3779B97F4A7C15;
    return @intCast(x >> 17);
}
fn smGrow() void {
    const old = sm_tab;
    const oc = sm_cap;
    sm_cap = if (oc != 0) oc * 2 else 1 << 14;
    sm_tab = @ptrCast(@alignCast(host.calloc(sm_cap * @sizeOf(SmEnt))));
    sm_n = 0;
    if (old) |o| {
        var i: usize = 0;
        while (i < oc) : (i += 1) if (o[i].k) |k| {
            const e = smFind(k, true).?;
            e.crc = o[i].crc;
            e.col = o[i].col;
        };
        host.free(@ptrCast(o));
    }
}
fn smFind(p: *anyopaque, ins: bool) ?*SmEnt {
    if (ins and (sm_n + 1) * 2 > sm_cap) smGrow();
    if (sm_cap == 0) return null;
    const tab = sm_tab.?;
    const m = sm_cap - 1;
    var i = smHash(p) & m;
    while (tab[i].k) |k| {
        if (k == p) return &tab[i];
        i = (i + 1) & m;
    }
    if (!ins) return null;
    tab[i] = .{ .k = p, .crc = 0, .col = SM_NONE };
    sm_n += 1;
    return &tab[i];
}
fn smOpaque(t: *anyopaque) bool {
    const h = obj.header(t).*;
    if (h & SEEN_BIT != 0) {
        sm_hit_seen = true;
        return true;
    }
    if (h & STABLE_BIT != 0) {
        sm_hit_stable = true;
        return true;
    }
    return (h & (CAND_BIT | DEAD_BIT)) != 0 or (h & kx.CNT_MASK) == 0;
}
fn smMgChild(t: ?*anyopaque) callconv(.c) void {
    const p = t orelse return;
    if (!isTraced(p) or smOpaque(p)) return;
    const e = smFind(p, true).?;
    e.crc += 1;
    dbg_side_visits += 1;
    if (e.col != SM_GRAY) {
        if (e.col == SM_NONE) clrBit(p, DIRTY_BIT);
        e.col = SM_GRAY;
        sm_work.push(p);
    }
}
fn smSbChild(t: ?*anyopaque) callconv(.c) void {
    const p = t orelse return;
    if (!isTraced(p)) return;
    if (smFind(p, false)) |e| if (e.col != SM_BLACK) {
        e.col = SM_BLACK;
        promote(p);
        sm_black.push(p);
    };
}
fn smScanChild(t: ?*anyopaque) callconv(.c) void {
    const p = t orelse return;
    if (isTraced(p) and smFind(p, false) != null) sm_work.push(p);
}
fn smCwChild(t: ?*anyopaque) callconv(.c) void {
    const p = t orelse return;
    if (!isTraced(p) or has(p, CAND_BIT)) return;
    if (smFind(p, false)) |e| if (e.col == SM_WHITE) {
        e.col = SM_BLACK;
        setBit(p, CAND_BIT);
        setCrc(p, cw_id & CRC_MAX);
        cw_m.push(p);
        c_work.push(p);
    };
}
fn sideMinor() void {
    dbg_side_minors += 1;
    gc_st.collections += 1;
    var roots = c_roots;
    c_roots = .{};
    if (slice_roots != 0 and roots.len > slice_roots) {
        var j = slice_roots;
        while (j < roots.len) : (j += 1) c_roots.push(roots.at(j));
        roots.len = slice_roots;
    }
    sm_roots.len = 0;
    var i: usize = 0;
    while (i < roots.len) : (i += 1) { // pass 1, as in detectPrepare
        const x = roots.at(i).?;
        clrBit(x, kx.BUF_BIT);
        if (has(x, ZOMBIE_BIT)) {
            clrBit(x, ZOMBIE_BIT);
            retPush(x);
            roots.set(i, null);
            continue;
        }
        if (has(x, DEAD_BIT) and !has(x, CAND_BIT)) {
            clrBit(x, DEAD_BIT);
            releaseDead(x);
            roots.set(i, null);
        }
    }
    i = 0;
    while (i < roots.len) : (i += 1) {
        const x = roots.at(i) orelse continue;
        if (has(x, CAND_BIT | DEAD_BIT) or obj.refCount(x) == 0) continue;
        if (has(x, SEEN_BIT)) {
            setBit(x, kx.BUF_BIT);
            sm_retry.push(x);
            continue;
        }
        obj.setColor(x, obj.BLACK); // leaves the root buffer; the major may trace it like any object
        const e = smFind(x, true).?;
        if (e.col != SM_NONE) continue;
        e.col = SM_GRAY;
        clrBit(x, DIRTY_BIT);
        sm_roots.push(x);
        sm_work.push(x);
    }
    roots.deinit();
    sm_hit_seen = false;
    sm_hit_stable = false;
    while (sm_work.len != 0) travIt(sm_work.pop().?, smMgChild); // MarkGray
    i = 0;
    while (i < sm_roots.len) : (i += 1) sm_work.push(sm_roots.at(i)); // Scan
    while (sm_work.len != 0 or sm_black.len != 0) {
        if (sm_black.len != 0) {
            travIt(sm_black.pop().?, smSbChild);
            continue;
        }
        const x = sm_work.pop().?;
        const e = smFind(x, false) orelse continue;
        if (e.col != SM_GRAY) continue;
        if (@as(i64, @intCast(obj.refCount(x))) - @as(i64, e.crc) > 0) {
            e.col = SM_BLACK;
            promote(x);
            sm_black.push(x);
        } else {
            e.col = SM_WHITE;
            travIt(x, smScanChild);
        }
    }
    i = 0;
    while (i < sm_roots.len) : (i += 1) { // CollectWhite
        const x = sm_roots.at(i).?;
        const e = smFind(x, false).?;
        if (e.col != SM_WHITE or has(x, CAND_BIT)) continue;
        if (cw_ends.len == 0) cw_first_id = next_set_id;
        cw_id = next_set_id;
        next_set_id += 1;
        e.col = SM_BLACK;
        setBit(x, CAND_BIT);
        setCrc(x, cw_id & CRC_MAX);
        cw_m.push(x);
        c_work.push(x);
        while (c_work.len != 0) travIt(c_work.pop().?, smCwChild);
        cw_ends.push(@ptrFromInt(cw_m.len));
    }
    i = 0;
    while (i < sm_roots.len) : (i += 1) { // live-looking roots whose trace was cut short: retry later
        const x = sm_roots.at(i).?;
        if (has(x, CAND_BIT) or has(x, kx.BUF_BIT)) continue;
        if (sm_hit_seen) {
            obj.setColor(x, obj.PURPLE);
            setBit(x, kx.BUF_BIT);
            sm_retry.push(x);
        } else if (sm_hit_stable) {
            setBit(x, kx.BUF_BIT);
            c_deferred.push(x);
        }
    }
    if (sm_tab) |t| @memset(t[0..sm_cap], .{ .k = null, .crc = 0, .col = SM_NONE });
    sm_n = 0;
    closeSets();
}

fn imEnvInit() void {
    if (incremental_major >= 0) return;
    const e = std.c.getenv("KX_GC_INC_MAJOR");
    incremental_major = if (e != null and e.?[0] != 0) @intFromBool(e.?[0] != '0') else 1;
    im_step_budget = host.getenv_i64("KX_GC_MAJOR_STEP", MAJOR_STEP);
    const sm = std.c.getenv("KX_GC_SIDE_MINOR");
    side_minors = if (sm != null and sm.?[0] != 0) sm.?[0] != '0' else true;
    im_step_ns = host.getenv_i64("KX_GC_MAJOR_STEP_US", MAJOR_STEP_US) * 1000;
    slice_roots = @intCast(host.getenv_i64("KX_GC_SLICE_ROOTS", SLICE_ROOTS));
    const mr = host.getenv_i64("KX_GC_MAJOR_RATIO", 0);
    if (mr > 0) major_ratio = mr;
}

fn flushLiveDef() void {
    var i: usize = 0;
    while (i < c_live_def.len) : (i += 1) {
        const s = c_live_def.at(i).?;
        if (has(s, ZOMBIE_BIT)) {
            clrBit(s, ZOMBIE_BIT);
            clrBit(s, kx.BUF_BIT);
            retPush(s);
            continue;
        }
        if (has(s, STABLE_BIT)) {
            clrBit(s, STABLE_BIT);
            c_stable_n -= 1;
        }
        c_roots.push(s);
    }
    c_live_def.len = 0;
    c_zombies = 0;
}

fn fullCycle() void {
    while (im_state != 0 and !imStep(std.math.maxInt(i64))) {}
    while (true) {
        var freed = validateReady(0, true);
        if (c_roots.len != 0 or c_deferred.len != 0 or c_live_def.len != 0) {
            detect(true);
            freed += validateReady(0, true);
        }
        gc_st.reclaimed += @intCast(freed);
        if (freed == 0 and c_roots.len == 0 and c_deferred.len == 0 and c_live_def.len == 0 and cands == null) break;
    }
}

fn publishReturns() void {
    if (c_ret.len == 0) return;
    lock();
    obj.PtrStack.moveFrom(&ret_shared, &c_ret);
    unlock();
}

fn collectorMain() void {
    lock();
    while (true) {
        while (q_head == null and !full_req and !shutting) {
            if (im_state != 0) { // idle collector: advance the incremental major
                unlock();
                _ = imStep(im_step_budget);
                publishReturns();
                lock();
                continue;
            }
            waitWork();
        }
        if (q_head) |l| {
            q_head = l.next;
            if (q_head == null) q_tail = null;
            q_len -= 1;
            broadcastDone();
            unlock();
            var i: usize = 0;
            while (i < l.frees.len) : (i += 1) host.free(l.frees.at(i));
            l.frees.len = 0;
            applyLog(l);
            if (c_live_def.len >= 4096 and (@as(i64, @intCast(c_live_def.len)) * defer_flush >= c_stable_n or c_live_def.len >= 65536)) flushLiveDef();
            const freed = validateReady(l.seq, false);
            imEnvInit();
            const major_at = major_ratio * @max(c_stable_n, @as(i64, @intCast(c_threshold)));
            if (im_state != 0) {
                const td = host.rt_nanos();
                // per-log slice scales with the log size (gc.c collector_main)
                const slice: i64 = if (l.n < LOG_CAP) @divTrunc(im_step_ns * @as(i64, @intCast(l.n)), LOG_CAP) else im_step_ns;
                if (im_step_ns > 0) {
                    while (!imStep(512) and host.rt_nanos() - td < slice) {}
                } else _ = imStep(im_step_budget);
                if (im_state != 0 and side_minors and c_roots.len >= c_threshold) sideMinor();
            } else if (incremental_major != 0 and (@as(i64, @intCast(c_deferred.len)) >= major_at or dbg_force_im.swap(false, .acq_rel))) {
                gc_st.collections += 1;
                imBegin();
            } else if (c_roots.len >= c_threshold or @as(i64, @intCast(c_deferred.len)) >= major_at) {
                const major = @as(i64, @intCast(c_deferred.len)) >= major_at;
                detect(major);
                gc_st.collections += 1;
            }
            gc_st.reclaimed += @intCast(freed);
            publishReturns();
            lock();
            applied = l.seq;
            // C: keep at most max_queued+2 spare logs in the pool (queue_spike: logs <= 20)
            if (pool_len > max_queued + 2) {
                l.frees.deinit();
                host.free(l);
            } else {
                l.next = log_pool;
                log_pool = l;
                pool_len += 1;
            }
            broadcastDone();
        } else if (full_req) {
            unlock();
            // At the quiescent queue all handed logs are applied; release deferred owner collections too.
            freeHeld(true);
            fullCycle();
            publishReturns();
            lock();
            full_req = false;
            full_done += 1;
            broadcastDone();
        } else break;
    }
    unlock();
}

fn mutReleaseChild(t: ?*anyopaque) callconv(.c) void {
    if (t) |p| if (!isTraced(p)) if (obj.vtab(p).rel) |rel| rel(p);
}

const HeldOwner = struct { p: *anyopaque, seq: u64 };
var m_held = std.ArrayListUnmanaged(HeldOwner).empty;
fn freeHeld(all: bool) void {
    var i: usize = 0;
    var kept: usize = 0;
    while (i < m_held.items.len) : (i += 1) {
        const h = m_held.items[i];
        if (all or h.seq <= m_applied) obj.vtab(h.p).freemem.?(h.p) else {
            m_held.items[kept] = h;
            kept += 1;
        }
    }
    m_held.items.len = kept;
}

fn freeReturned(p: *anyopaque) void {
    // C free_returned: one vtab load, unconditional trav/relo/freemem calls.
    const vt = obj.vtab(p);
    host.rt_trav_mode = kx.TRAV_FREE;
    vt.trav.?(p, mutReleaseChild);
    host.rt_trav_mode = kx.TRAV_SCAN;
    host.rt_relo_owner = 0;
    vt.relo.?(p);
    if (host.rt_relo_owner == 0) {
        vt.freemem.?(p);
        return;
    }
    // Collection owners may log operations after their own death. Do not free the owner
    // until the log containing those operations is applied (C m_held/free_held).
    if (m_held.items.len == m_held.capacity) {
        const n = if (m_held.capacity == 0) 64 else m_held.capacity * 2;
        const raw: [*]HeldOwner = @ptrCast(@alignCast(host.realloc(if (m_held.capacity == 0) null else @ptrCast(m_held.items.ptr), n * @sizeOf(HeldOwner))));
        m_held.items.ptr = raw;
        m_held.capacity = n;
    }
    m_held.appendAssumeCapacity(.{ .p = p, .seq = if (cur != null and cur.?.n != 0) handed + 1 else handed });
}

fn processReturns(budget_ns: i64, t0: i64, keep: usize) void {
    if (m_held.items.len != 0) freeHeld(false);
    // C process_returns: free only excess over `keep`, within free_budget_ns; paced frees the rest.
    while (ret_pos + keep < ret_local.len) {
        // Match C's ret_local.items[ret_pos++]: relo may fill the log and
        // re-enter boundary/processReturns, so consume the entry before callbacks.
        const p = ret_local.at(ret_pos).?;
        ret_pos += 1;
        freeReturned(p);
        dbg_bfreed += 1;
        if (budget_ns >= 0 and (ret_pos & 31) == 0 and host.rt_nanos() - t0 > budget_ns) break;
    }
    if (ret_pos == ret_local.len) {
        ret_local.len = 0;
        ret_pos = 0;
    } else if (ret_pos >= 4096 and ret_pos * 2 >= ret_local.len) {
        // keep the pending tail at the front (gc.c)
        const rem = ret_local.len - ret_pos;
        var i: usize = 0;
        while (i < rem) : (i += 1) ret_local.set(i, ret_local.at(ret_pos + i));
        ret_local.len = rem;
        ret_pos = 0;
    }
}

// C free_returned stays out of line. Inlining the paced loop into allocTrace
// made newObj too fat for op_alloc (no-LTO `call newObj` every iteration).
noinline fn freePaced() void {
    if (ret_pos >= ret_local.len) return;
    const back = ret_local.len - ret_pos;
    var k: usize = if (back > 65536) 16 else if (back > 16384) 8 else if (back > 4096) 4 else 2;
    while (k > 0 and ret_pos < ret_local.len) : (k -= 1) {
        // Match C's ret_local.items[ret_pos++]: relo may fill the log and
        // re-enter boundary/processReturns, so consume the entry before callbacks.
        const p = ret_local.at(ret_pos).?;
        ret_pos += 1;
        freeReturned(p);
        dbg_paced += 1;
    }
    if (ret_pos == ret_local.len) {
        ret_local.len = 0;
        ret_pos = 0;
    }
}

// C: `__attribute__((noinline)) void log_first` so log_op has no stack frame.
noinline fn logFirst() void {
    const e = std.c.getenv("KX_GC_LOG_CAP");
    if (e != null and e.?[0] != 0) {
        var cap: usize = @intCast(host.getenv_i64("KX_GC_LOG_CAP", 512));
        if (cap > LOG_CAP) cap = LOG_CAP;
        log_cap.store(cap, .monotonic);
        log_cap_fixed = true;
    } else logCapFollow();
    lock();
    cur = logNew();
    unlock();
}

inline fn logOp(e: usize) void {
    // C log_op: `if (cur == NULL) log_first(); cur->a[cur->n++] = e; if (n >= cap) boundary();`
    if (cur == null) {
        @branchHint(.unlikely);
        logFirst();
    }
    const l = cur.?;
    const n = l.n;
    l.a[n] = e;
    const n1 = n + 1;
    l.n = n1;
    if (n1 >= log_cap.load(.monotonic)) {
        @branchHint(.unlikely);
        boundary();
    }
}

/// Always-inline mutator API (C `always_inline` log_op / rt_gc_log_*). Export
/// wrappers stay for the C ABI; native ops must call these so Darwin (no LTO)
/// still inlines the log into the mutator.
pub inline fn logInc(p: ?*anyopaque) void {
    if (p) |q| logOp(@intFromPtr(q) | OP_INC);
}
pub inline fn logDec(p: ?*anyopaque) void {
    if (p) |q| logOp(@intFromPtr(q) | OP_DEC);
}
pub inline fn writeBarrier(o: ?*anyopaque) void {
    logOp(@intFromPtr(o) | OP_MOD);
}

// Out of line like C: inlining this into log_op gives every retain/store a fat frame.
noinline fn boundary() void {
    if (in_boundary) {
        lock();
        handed += 1;
        cur.?.seq = handed;
        if (q_tail) |t| t.next = cur else q_head = cur;
        q_tail = cur;
        q_len += 1;
        cur = null;
        signalWork();
        cur = logNew();
        unlock();
        return;
    }
    in_boundary = true;
    const t0 = host.rt_nanos();
    handoff();
    if (free_budget_ns < 0) {
        const e = std.c.getenv("KX_GC_FREE_BUDGET_US");
        if (e != null and e.?[0] != 0) {
            free_budget_ns = host.getenv_i64("KX_GC_FREE_BUDGET_US", 0) * 1000;
        } else {
            const half = @divTrunc(q_target_ns, 2);
            free_budget_ns = if (half > FREE_BUDGET_MIN_NS) half else FREE_BUDGET_MIN_NS;
        }
    }
    if (boundary_keep < 0) {
        const e = std.c.getenv("KX_GC_BOUNDARY_KEEP");
        boundary_keep = if (e != null and e.?[0] != 0) host.getenv_i64("KX_GC_BOUNDARY_KEEP", BOUNDARY_KEEP_DEFAULT) else BOUNDARY_KEEP_DEFAULT;
        if (boundary_keep < 0) boundary_keep = 0;
    }
    processReturns(free_budget_ns, t0, @intCast(boundary_keep));
    slabFlush();
    obj.statsAddPause(&gc_st, host.rt_nanos() - t0);
    in_boundary = false;
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
pub export fn rt_gc_root_hint(p: ?*anyopaque) void {
    logOp(@intFromPtr(p) | OP_ROOT);
}
pub export fn rt_gc_free_later(p: ?*anyopaque) void {
    const q = p orelse return;
    if (cur == null) {
        host.free(q);
        return;
    }
    cur.?.frees.push(q);
}

fn exitDrain() bool {
    return host.getenv_present("KX_GC_EXIT_DRAIN") or host.getenv_present("KX_GC_STATS");
}

fn epochDebug() void {
    const e = std.c.getenv("KX_GC_DEBUG");
    if (e == null or e.?[0] == 0) return;
    // archive/run.sh parses qmax=, logs=, paced=/boundary=, madvised=
    std.debug.print("epoch: qmax={d} q={d}\n", .{ dbg_q_max, max_queued });
    std.debug.print("epoch: freed paced={d} boundary={d} keep={d} madvised={d} stack-released=0\n", .{ dbg_paced, dbg_bfreed, boundary_keep, dbg_madvised });
    std.debug.print("epoch: mem MiB slabs=0.0 logs={d}(0.0)\n", .{dbg_log_allocs});
}

pub export fn rt_gc_collect() void {
    if (in_boundary) return;
    // Native tests invoke drain themselves; runtime.c invokes it on exit when requested.
    // Print requested counters after the final pass, including queue_spike's peak values.
    in_boundary = true;
    const t0 = host.rt_nanos();
    while (true) {
        const had = cur != null and (cur.?.n != 0 or cur.?.frees.len != 0);
        handoff();
        lock();
        while (q_head != null) waitDone();
        const want = full_done + 1;
        full_req = true;
        signalWork();
        while (full_done < want) waitDone();
        obj.PtrStack.moveFrom(&ret_local, &ret_shared);
        m_applied = applied;
        unlock();
        const returned = ret_local.len - ret_pos;
        processReturns(-1, t0, 0);
        if (!had and returned == 0 and !(cur != null and (cur.?.n != 0 or cur.?.frees.len != 0))) break;
    }
    obj.statsAddPause(&gc_st, host.rt_nanos() - t0);
    in_boundary = false;
    epochDebug();
}

pub export fn rt_gc_name() [*:0]const u8 {
    return "epoch";
}
pub export fn rt_gc_stats(out: *kx.Stats) void {
    lock();
    out.* = gc_st;
    unlock();
}


/// Test hook: incremental-major / side-minor counters (read after rt_gc_collect).
pub const IncStats = struct { im_steps: i64, side_minors: i64, side_visits: i64, held: i64, held_max: i64 };
pub fn debugRequestIncMajor() void {
    dbg_force_im.store(true, .release);
}
pub fn debugIncStats() IncStats {
    lock();
    defer unlock();
    return .{ .im_steps = im_steps, .side_minors = dbg_side_minors, .side_visits = dbg_side_visits, .held = dbg_held, .held_max = dbg_held_max };
}
