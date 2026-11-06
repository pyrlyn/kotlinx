//! Standalone regression for the epoch incremental major + side minors (gc.c im_step / side_minor).
//! Live old-generation ring mutated and re-rooted while garbage cycles churn, then a full drain:
//! every allocation must be freed exactly once, no freed object may be traced.
//! Build: zig build-exe -Mroot=tests/native/epoch_incmajor.zig --dep collector --dep kxgc --dep host ... (see README note)
const std = @import("std");
const gc = @import("collector");
const gc_host = @import("host");
const kx = @import("kxgc");

const LIVE: u64 = 0x11FE11FE;
const Two = extern struct { hdr: u64, vt: *kx.VTab, a: ?*Two, b: ?*Two, magic: u64 };
var allocated: i64 = 0;
var freed: i64 = 0;
var bad: i64 = 0;
fn tv(p: ?*anyopaque, f: ?kx.VisitFn) callconv(.c) void {
    const o: *Two = @ptrCast(@alignCast(p));
    if (o.magic != LIVE) bad += 1;
    f.?(o.a);
    f.?(o.b);
}
fn relo(_: ?*anyopaque) callconv(.c) void {}
fn fr(p: ?*anyopaque) callconv(.c) void {
    const o: *Two = @ptrCast(@alignCast(p));
    if (o.magic != LIVE) bad += 1;
    o.magic = 0;
    freed += 1;
    gc.rt_free_trace(p, @sizeOf(Two));
}
var vt: kx.VTab = .{ .rel = null, .trav = tv, .relo = relo, .freemem = fr, .cyclic = @ptrFromInt(1) };
var owner_enabled: bool = false;
fn reloOwner(owner_obj: ?*anyopaque) callconv(.c) void {
    if (owner_enabled) gc.rt_gc_log_inc(owner_obj);
    gc_host.rt_relo_owner = if (owner_enabled) 1 else 0;
}
var owner_vt: kx.VTab = .{ .rel = null, .trav = tv, .relo = reloOwner, .freemem = fr, .cyclic = @ptrFromInt(1) };

fn newWithVt(v: *kx.VTab) *Two {
    const o: *Two = @ptrCast(@alignCast(gc.rt_alloc_trace(@sizeOf(Two)).?));
    o.* = .{ .hdr = 1, .vt = v, .a = null, .b = null, .magic = LIVE };
    allocated += 1;
    return o;
}
fn new() *Two {
    return newWithVt(&vt);
}

/// Deferred-RC field store: inc new, barrier, dec old.
fn store(slot: *?*Two, owner: *Two, v: ?*Two) void {
    if (v) |n| gc.rt_gc_log_inc(n);
    const old = slot.*;
    slot.* = v;
    gc.rt_gc_write_barrier(owner);
    if (old) |o| gc.rt_gc_log_dec(o);
}

pub fn main() u8 {
    const N: usize = 5_000;
    const H: usize = 20_000;
    const ROUNDS: usize = 40;
    var prng = std.Random.DefaultPrng.init(42);
    const r = prng.random();
    const ring = std.heap.page_allocator.alloc(*Two, N) catch return 2;
    const holders = std.heap.page_allocator.alloc(*Two, H) catch return 2;
    for (ring) |*s| s.* = new(); // each held by the local array (count 1)
    for (ring, 0..) |o, i| store(&o.a, o, ring[(i + 1) % N]);
    var round: usize = 0;
    while (round < ROUNDS) : (round += 1) {
        var i: usize = 0;
        while (i < N / 2) : (i += 1) {
            if (i % 8192 == 0) gc.debugRequestIncMajor();
            const j = r.uintLessThan(usize, N);
            // garbage 2-cycle hanging off a live node, then cut off again
            const x = new();
            const y = new();
            store(&x.a, x, y);
            store(&y.a, y, x);
            gc.rt_gc_log_dec(y);
            store(&ring[j].b, ring[j], x); // live -> cycle
            gc.rt_gc_log_dec(x);
            if (r.boolean()) store(&ring[j].b, ring[j], null); // cycle now garbage
            // live churn: re-point to another live node (decrements stable objects)
            const k = r.uintLessThan(usize, N);
            store(&ring[k].b, ring[k], ring[r.uintLessThan(usize, N)]);
        }
        // young live holders into the old ring: their minors stop at stable objects and are
        // deferred, which is what starts a major; each holder is re-rooted by an inc/dec pair
        for (holders, 0..) |*h, hi| {
            if (hi % 4096 == 0) gc.debugRequestIncMajor();
            h.* = new();
            store(&h.*.a, h.*, ring[r.uintLessThan(usize, N)]);
            const g = new(); // garbage cycle through a holder: freed only by a cycle detection
            store(&g.a, g, h.*);
            store(&h.*.b, h.*, g);
            gc.rt_gc_log_dec(g);
            gc.rt_gc_log_inc(h.*);
            gc.rt_gc_log_dec(h.*);
            // sliding window: holders die while a major that may have visited them is running
            const W = 3000;
            if (hi >= W) gc.rt_gc_log_dec(holders[hi - W]);
            // acyclic leaves under old nodes, replaced (count -> 0) while a major may have visited them
            {
                const leaf = new();
                const q = ring[r.uintLessThan(usize, N)];
                store(&q.b, q, leaf);
                gc.rt_gc_log_dec(leaf);
            }
        }
        var w: usize = if (H > 3000) H - 3000 else 0;
        while (w < H) : (w += 1) gc.rt_gc_log_dec(holders[w]);
    }
    // Collections use rt_relo_owner to defer frees until operations issued by relo are applied.
    // Exercise that protocol, including owner-log operations, not just plain traversal.
    owner_enabled = true;
    var owner_i: usize = 0;
    while (owner_i < 1000) : (owner_i += 1) {
        const h = newWithVt(&owner_vt);
        store(&h.a, h, h); // unreachable one-object cycle
        gc.rt_gc_log_dec(h);
    }
    gc.rt_gc_collect();
    owner_enabled = false;

    // drop the external references: the whole ring (and what hangs off it) becomes garbage
    for (ring) |o| gc.rt_gc_log_dec(o);
    var d: usize = 0;
    while (d < 4) : (d += 1) gc.rt_gc_collect();
    const s = gc.debugIncStats();
    var st: kx.Stats = undefined;
    gc.rt_gc_stats(&st);
    std.debug.print("allocated={d} freed={d} bad={d} collections={d} im_steps={d} side_minors={d} side_visits={d} held={d} held_max={d}\n", .{ allocated, freed, bad, st.collections, s.im_steps, s.side_minors, s.side_visits, s.held, s.held_max });
    if (freed != allocated or bad != 0 or s.im_steps == 0 or s.side_minors == 0 or s.held == 0) {
        std.debug.print("EPOCH INCMAJOR FAILED\n", .{});
        return 1;
    }
    std.debug.print("EPOCH INCMAJOR OK\n", .{});
    return 0;
}
