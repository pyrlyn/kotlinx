//! Native tests for the compact collector (src/compact.zig). It keeps no statistics, which tests/native/suite.zig
//! checks, so it has this suite instead. Objects emulate the code newkotlinc generates: Obj is a possibly cyclic
//! class (two traced fields and an acyclic payload), Pay an acyclic class that may hold a reference to an Obj.
const std = @import("std");
const gc = @import("collector");
const kx = @import("kxgc");
const host = @import("host");

const Obj = extern struct {
    hdr: u64,
    vt: *const kx.VTab,
    a: ?*Obj,
    b: ?*Obj,
    p: ?*Pay,
    val: i64,
};
const Pay = extern struct {
    hdr: u64,
    vt: *const kx.VTab,
    ref: ?*Obj,
    val: i64,
};

var objs_live: i64 = 0;
var objs_freed: i64 = 0;
var pays_live: i64 = 0;
var relo_calls: i64 = 0;
var fails: u32 = 0;

fn check(cond: bool, what: []const u8) void {
    if (!cond) {
        std.debug.print("FAIL {s}\n", .{what});
        fails += 1;
    }
}

fn objTrav(p: ?*anyopaque, visit: ?kx.VisitFn) callconv(.c) void {
    const o: *Obj = @ptrCast(@alignCast(p));
    visit.?(o.a);
    visit.?(o.b);
    visit.?(o.p);
}
fn objRelo(_: ?*anyopaque) callconv(.c) void {
    relo_calls += 1;
}
fn objFree(p: ?*anyopaque) callconv(.c) void {
    objs_live -= 1;
    objs_freed += 1;
    gc.rt_free_trace(p, @sizeOf(Obj));
}
/// @rel.C and @relz.C of a cyclic class (NativeBackend): purple and buffered on a non-zero count; at zero, release
/// the fields, then free unless buffered (then black, left to the collector).
fn objRel(p: ?*anyopaque) callconv(.c) void {
    const o: *Obj = @ptrCast(@alignCast(p orelse return));
    const c1 = o.hdr -% 1;
    if (c1 & kx.CNT_MASK != 0) {
        if (c1 & kx.BUF_BIT != 0) {
            o.hdr = c1 | kx.COLOR_MASK;
            return;
        }
        o.hdr = c1;
        gc.rt_cc_possible_root(o);
        return;
    }
    o.hdr = c1;
    objRel(o.a);
    objRel(o.b);
    payRel(o.p);
    if (o.hdr & kx.BUF_BIT != 0) {
        o.hdr &= ~kx.COLOR_MASK;
        return;
    }
    objFree(o);
}
const obj_vt: kx.VTab = .{ .rel = objRel, .trav = objTrav, .relo = objRelo, .freemem = objFree, .cyclic = @ptrFromInt(1) };

fn payTrav(p: ?*anyopaque, visit: ?kx.VisitFn) callconv(.c) void {
    visit.?(@as(*Pay, @ptrCast(@alignCast(p))).ref);
}
fn payRelo(_: ?*anyopaque) callconv(.c) void {}
fn payFree(p: ?*anyopaque) callconv(.c) void {
    pays_live -= 1;
    gc.rt_free(p, @sizeOf(Pay));
}
/// @rel.C of an acyclic class: at zero release the fields and free.
fn payRel(p: ?*anyopaque) callconv(.c) void {
    const q: *Pay = @ptrCast(@alignCast(p orelse return));
    q.hdr -= 1;
    if (q.hdr != 0) return;
    objRel(q.ref);
    payFree(q);
}
const pay_vt: kx.VTab = .{ .rel = payRel, .trav = payTrav, .relo = payRelo, .freemem = payFree, .cyclic = null };

/// A new object with `refs` references (0: only the edges added later count).
fn newObj(v: i64, refs: u64) *Obj {
    const o: *Obj = @ptrCast(@alignCast(gc.rt_alloc_trace(@sizeOf(Obj)).?));
    o.* = .{ .hdr = refs, .vt = &obj_vt, .a = null, .b = null, .p = null, .val = v };
    objs_live += 1;
    return o;
}
fn newPay(v: i64, ref: ?*Obj) *Pay {
    const q: *Pay = @ptrCast(@alignCast(gc.rt_alloc(@sizeOf(Pay)).?));
    q.* = .{ .hdr = 1, .vt = &pay_vt, .ref = ref, .val = v };
    if (ref) |r| r.hdr += 1;
    pays_live += 1;
    return q;
}
fn countOf(o: *Obj) u64 {
    return o.hdr & kx.CNT_MASK;
}
fn linkA(from: *Obj, to: *Obj) void {
    from.a = to;
    to.hdr += 1;
}
fn linkB(from: *Obj, to: *Obj) void {
    from.b = to;
    to.hdr += 1;
}
/// A retain and a release: leaves o buffered (purple) at the same count.
fn touch(o: *Obj) void {
    o.hdr += 1;
    objRel(o);
}
fn isBlackUnbuffered(o: *Obj) bool {
    return gc.debugColor(o) == kx.BLACK and !gc.debugIsBuffered(o);
}
fn allocNodes(n: usize) []*Obj {
    return std.heap.page_allocator.alloc(*Obj, n) catch @panic("test allocation");
}
/// A ring of new objects over field a, each with a payload and still holding the caller's reference (count 2).
fn ring(nodes: []*Obj) void {
    for (nodes, 0..) |*slot, i| {
        slot.* = newObj(@intCast(i), 1);
        slot.*.p = newPay(@intCast(i), null);
    }
    for (nodes, 0..) |o, i| linkA(o, nodes[(i + 1) % nodes.len]);
}
/// Collections until nothing is buffered, as at program exit.
fn collect() void {
    gc.rt_gc_collect();
    check(gc.debugBuffered() == 0, "rt_gc_collect empties the buffer");
}

const FANOUT = 64;
const Node = extern struct {
    hdr: u64,
    vt: *const kx.VTab,
    edges: [FANOUT]?*Node,
};
var node_freed: usize = 0;
var node_released: usize = 0;
var node_visits: usize = 0;
fn nodeRel(_: ?*anyopaque) callconv(.c) void {
    @panic("the collector released a traced garbage object");
}
fn nodeFree(p: ?*anyopaque) callconv(.c) void {
    node_freed += 1;
    gc.rt_free_trace(p, @sizeOf(Node));
}
fn nodeRelo(_: ?*anyopaque) callconv(.c) void {
    node_released += 1;
}
fn nodeTrav(p: ?*anyopaque, visit: ?kx.VisitFn) callconv(.c) void {
    node_visits += 1;
    const n: *Node = @ptrCast(@alignCast(p));
    for (n.edges) |e| visit.?(e);
}
const node_vt: kx.VTab = .{ .rel = nodeRel, .trav = nodeTrav, .relo = nodeRelo, .freemem = nodeFree, .cyclic = @ptrFromInt(1) };
fn nodeNew() *Node {
    const n: *Node = @ptrCast(@alignCast(gc.rt_alloc_trace(@sizeOf(Node)).?));
    n.* = .{ .hdr = 0, .vt = &node_vt, .edges = @splat(null) };
    return n;
}
fn edge(from: *Node, slot: usize, to: *Node) void {
    from.edges[slot] = to;
    to.hdr += 1;
}

/// After archive/unit/traversal.c: every object is traversed once per phase that visits it, also with 64 edges to
/// the same child, and enters the array once. Phases: MarkGray and ScanBlack for live objects, MarkGray and
/// CollectWhite for garbage.
fn traversal(count: usize) void {
    const nodes = std.heap.page_allocator.alloc(*Node, count) catch @panic("test allocation");
    defer std.heap.page_allocator.free(nodes);
    for (nodes) |*n| n.* = nodeNew();
    for (nodes, 0..) |n, i| {
        for (0..FANOUT) |j| edge(n, j, nodes[(i + 1) % count]);
    }
    nodes[count - 1].hdr += 1;
    gc.rt_cc_possible_root(nodes[0]);
    collect();
    check(node_freed == 0 and node_released == 0 and node_visits == 2 * count, "live ring restored");
    for (nodes, 0..) |n, i| {
        check(gc.debugRefCount(n) == FANOUT + @as(u32, if (i == count - 1) 1 else 0), "ring count");
        check(gc.debugColor(n) == kx.BLACK and !gc.debugIsBuffered(n), "ring black");
    }
    check(gc.debugCapacity() <= 2 * count, "each object enters the array once");
    nodes[count - 1].hdr -= 1;
    gc.rt_cc_possible_root(nodes[0]);
    collect();
    check(node_freed == count and node_released == count and node_visits == 4 * count, "garbage ring");
    const a = nodeNew();
    const b = nodeNew();
    const c = nodeNew();
    edge(a, 0, b);
    edge(a, 1, c);
    edge(c, 0, b);
    edge(b, 0, a);
    c.hdr += 1;
    gc.rt_cc_possible_root(a);
    collect();
    check(node_freed == count and gc.debugRefCount(a) == 1 and gc.debugRefCount(b) == 2 and gc.debugRefCount(c) == 2, "sibling restore");
    c.hdr -= 1;
    gc.rt_cc_possible_root(c);
    collect();
    check(node_freed == count + 3 and node_released == count + 3, "sibling cycle reclaimed");
}

fn allocAndStorage() void {
    var i: i64 = 0;
    while (i < 1000) : (i += 1) {
        const p: [*]i64 = @ptrCast(@alignCast(gc.rt_alloc(48).?));
        p[5] = i;
        check(p[5] == i, "rt_alloc slot");
        gc.rt_free(p, 48);
        const t: [*]i64 = @ptrCast(@alignCast(gc.rt_alloc_trace(40).?));
        t[4] = i;
        check(t[4] == i, "rt_alloc_trace slot");
        gc.rt_free_trace(t, 40);
    }
    const m0: [*]i64 = @ptrCast(@alignCast(gc.rt_gc_mem_calloc(64).?));
    for (0..8) |j| check(m0[j] == 0, "calloc zeroes");
    m0[7] = 42;
    const m1: [*]i64 = @ptrCast(@alignCast(gc.rt_gc_mem_realloc(m0, 4096).?));
    check(m1[7] == 42, "realloc keeps the contents");
    const m2 = gc.rt_gc_mem_realloc(m1, 0);
    check(m2 != null, "realloc to zero bytes");
    gc.rt_gc_mem_free(m2);
    const z = gc.rt_gc_mem_realloc(null, 16);
    check(z != null, "realloc of NULL allocates");
    gc.rt_gc_free_later(z);
    const m3 = gc.rt_gc_mem_alloc(24);
    check(m3 != null, "mem_alloc");
    gc.rt_gc_mem_free(m3);
    check(std.mem.eql(u8, std.mem.span(gc.rt_gc_name()), "compact"), "name");
    var st: kx.Stats = .{ .collections = 7, .reclaimed = 7 };
    gc.rt_gc_stats(&st);
    check(st.collections == 0 and st.reclaimed == 0 and st.pause_ns_total == 0 and st.pause_ns_max == 0, "no statistics");
}

fn acyclicAndShared() void {
    const f0 = objs_freed;
    const a = newObj(1, 1);
    linkA(a, newObj(2, 0));
    a.p = newPay(3, null);
    objRel(a);
    check(objs_freed == f0 + 2 and pays_live == 0 and gc.debugBuffered() == 0, "acyclic chain freed by RC");
    const s = newObj(4, 2);
    objRel(s);
    check(gc.debugIsBuffered(s) and gc.debugColor(s) == kx.PURPLE and countOf(s) == 1, "release to non-zero buffers");
    collect();
    check(objs_freed == f0 + 2 and isBlackUnbuffered(s) and countOf(s) == 1 and s.val == 4, "shared object survives");
    objRel(s);
    check(objs_freed == f0 + 3, "last release frees an unbuffered object at once");
}

/// Garbage of several shapes: self-loop, 2-ring, 100-ring, doubly linked list, tree with parent edges.
fn shapes() void {
    for ([_]usize{ 1, 2, 100 }) |n| {
        const f0 = objs_freed;
        const r0 = relo_calls;
        const nodes = allocNodes(n);
        defer std.heap.page_allocator.free(nodes);
        ring(nodes);
        for (nodes) |o| objRel(o);
        check(objs_freed == f0, "a ring waits for the collector");
        collect();
        check(objs_freed == f0 + @as(i64, @intCast(n)) and relo_calls == r0 + @as(i64, @intCast(n)) and pays_live == 0, "garbage ring");
    }
    {
        const f0 = objs_freed;
        const nodes = allocNodes(100);
        defer std.heap.page_allocator.free(nodes);
        for (nodes, 0..) |*slot, i| slot.* = newObj(@intCast(i), 1);
        for (0..99) |i| {
            linkA(nodes[i], nodes[i + 1]);
            linkB(nodes[i + 1], nodes[i]);
        }
        for (nodes) |o| objRel(o);
        collect();
        check(objs_freed == f0 + 100, "doubly linked list");
    }
    {
        const f0 = objs_freed;
        const p0 = pays_live;
        const n = 1023;
        const nodes = allocNodes(n);
        defer std.heap.page_allocator.free(nodes);
        // t is held only by payloads of tree nodes: their releases buffer it while the tree is freed.
        const t = newObj(-5, 0);
        for (nodes, 0..) |*slot, i| {
            slot.* = newObj(@intCast(i), if (i == 0) 1 else 0);
            slot.*.p = newPay(@intCast(i), if (i % 7 == 0) t else null);
        }
        for (1..n) |i| {
            const parent = nodes[(i - 1) / 2];
            if (i % 2 == 1) linkA(parent, nodes[i]) else linkB(parent, nodes[i]);
        }
        // every leaf links back to the root
        for (n / 2..n) |i| linkA(nodes[i], nodes[0]);
        objRel(nodes[0]);
        collect();
        check(objs_freed == f0 + n + 1 and pays_live == p0, "tree with back edges");
    }
}

/// Live and garbage cycles linked both ways: only the garbage goes, and the live counts are exact afterwards.
fn partialGarbage() void {
    const f0 = objs_freed;
    const p0 = pays_live;
    var live: [5]*Obj = undefined;
    var dead: [7]*Obj = undefined;
    ring(&live);
    ring(&dead);
    for (live[1..]) |o| objRel(o);
    linkB(dead[3], live[2]); // garbage -> live
    linkB(live[4], dead[0]); // live -> garbage-to-be: keeps dead alive for now
    const shared = newPay(9, null);
    shared.hdr += 1;
    payRel(dead[5].p);
    dead[5].p = shared; // payload shared by a garbage object and the test
    for (dead) |o| objRel(o);
    collect();
    check(objs_freed == f0, "garbage reachable from a live ring survives");
    for (dead, 0..) |o, i| check(countOf(o) == 1 + @as(u64, if (i == 0) 1 else 0) and isBlackUnbuffered(o), "counts restored below live");
    objRel(live[4].b);
    live[4].b = null;
    collect();
    check(objs_freed == f0 + 7, "dropped garbage ring");
    check(countOf(live[0]) == 2 and countOf(live[2]) == 1 and countOf(live[1]) == 1, "live counts exclude the dead references");
    check(shared.hdr == 1 and shared.val == 9, "shared payload released once");
    payRel(shared);
    objRel(live[0]);
    collect();
    check(objs_freed == f0 + 12 and pays_live == p0, "live ring dropped");
}

/// Chains of a million objects: traversal is iterative and the live counts come back exactly.
fn deepChain(n: usize) void {
    const f0 = objs_freed;
    const r0 = relo_calls;
    const first = newObj(0, 1);
    var prev = first;
    var mid: *Obj = first;
    for (1..n) |i| {
        const o = newObj(@intCast(i), 0);
        linkA(prev, o);
        prev = o;
        if (i == n / 2) mid = o;
    }
    linkA(prev, first);
    gc.rt_cc_possible_root(first);
    collect();
    check(objs_freed == f0 and countOf(first) == 2 and countOf(mid) == 1 and countOf(prev) == 1, "live deep ring counts");
    check(isBlackUnbuffered(first) and isBlackUnbuffered(mid), "live deep ring black");
    objRel(first);
    collect();
    check(objs_freed == f0 + @as(i64, @intCast(n)) and relo_calls == r0 + @as(i64, @intCast(n)), "deep garbage ring");
}

fn bufferedThenFreed() void {
    const f0 = objs_freed;
    const p0 = pays_live;
    const x = newObj(1, 2);
    x.p = newPay(1, null);
    objRel(x);
    check(gc.debugIsBuffered(x) and gc.debugColor(x) == kx.PURPLE, "buffered purple");
    objRel(x);
    check(objs_freed == f0 and pays_live == p0 and gc.debugColor(x) == kx.BLACK and gc.debugIsBuffered(x), "dead buffered object waits, fields released");
    collect();
    check(objs_freed == f0 + 1, "the next collection frees it");
}

fn bufferedThenResurrected() void {
    const f0 = objs_freed;
    const p0 = pays_live;
    var r: [3]*Obj = undefined;
    ring(&r);
    for (r) |o| objRel(o);
    r[0].hdr += 1; // a new reference before any collection
    collect();
    check(objs_freed == f0 and pays_live == p0 + 3, "resurrected ring survives");
    check(countOf(r[0]) == 2 and countOf(r[1]) == 1 and countOf(r[2]) == 1, "resurrected ring counts");
    for (r) |o| check(isBlackUnbuffered(o), "resurrected ring black");
    objRel(r[0]);
    collect();
    check(objs_freed == f0 + 3 and pays_live == p0, "then collected");
}

/// Releases of what the garbage holds run after its traversal and may buffer new roots; collections started
/// by the threshold keep them for the next one.
fn releasesDuringFree() void {
    const f0 = objs_freed;
    const p0 = pays_live;
    // x is held by the test and by the payload of a garbage ring node.
    const x = newObj(7, 1);
    var r: [3]*Obj = undefined;
    ring(&r);
    payRel(r[1].p);
    r[1].p = newPay(-1, x);
    for (r) |o| objRel(o);
    // a ring reachable only through the payload of another garbage ring
    var inner: [4]*Obj = undefined;
    var outer: [2]*Obj = undefined;
    ring(&inner);
    ring(&outer);
    payRel(outer[0].p);
    outer[0].p = newPay(-2, inner[2]);
    for (inner) |o| objRel(o);
    for (outer) |o| objRel(o);
    // garbage self-loops until the threshold starts a collection
    var loops: i64 = 0;
    while (objs_freed == f0) {
        const s = newObj(-3, 1);
        linkA(s, s);
        objRel(s);
        loops += 1;
    }
    check(objs_freed == f0 + 3 + 2 + loops, "threshold collection");
    check(gc.debugIsBuffered(x) and countOf(x) == 1 and gc.debugIsBuffered(inner[2]), "new roots kept for the next collection");
    check(gc.debugBuffered() == 2, "only the new roots stay buffered");
    objRel(x);
    check(gc.debugColor(x) == kx.BLACK and objs_freed == f0 + 5 + loops, "new root dead, waits");
    collect();
    check(objs_freed == f0 + 3 + 2 + 4 + 1 + loops and pays_live == p0, "inner ring and x freed");
}

fn ccDeadAndHint() void {
    const f0 = objs_freed;
    const x = newObj(1, 1);
    x.hdr -= 1;
    gc.rt_cc_dead(x);
    check(objs_freed == f0 + 1, "rt_cc_dead frees an unbuffered object");
    const y = newObj(2, 2);
    objRel(y);
    y.hdr -= 1;
    gc.rt_cc_dead(y);
    check(objs_freed == f0 + 1 and gc.debugColor(y) == kx.BLACK and gc.debugIsBuffered(y), "rt_cc_dead leaves a buffered object");
    collect();
    check(objs_freed == f0 + 2, "collected later");
    const h = newObj(3, 1);
    gc.rt_gc_root_hint(h);
    check(gc.debugIsBuffered(h) and gc.debugColor(h) == kx.PURPLE, "root hint buffers");
    gc.rt_gc_root_hint(h);
    check(gc.debugBuffered() == 1, "once");
    collect();
    check(isBlackUnbuffered(h) and countOf(h) == 1, "hinted live object survives");
    h.hdr -= 1;
    gc.rt_gc_root_hint(h);
    gc.rt_gc_root_hint(null);
    check(!gc.debugIsBuffered(h) and gc.debugBuffered() == 0, "hint at count zero is a no-op");
    objFree(h);
}

fn manyRoots() void {
    const f0 = objs_freed;
    const pairs = 60_000;
    var i: i64 = 0;
    while (i < pairs) : (i += 1) {
        const a = newObj(i, 1);
        const b = newObj(i, 1);
        linkA(a, b);
        linkA(b, a);
        objRel(a);
        objRel(b);
    }
    check(objs_freed > f0 and gc.debugBuffered() < gc.debugThreshold(), "collections start on their own");
    collect();
    check(objs_freed == f0 + 2 * pairs, "all pairs reclaimed");
    check(gc.debugThreshold() == gc.THRESHOLD_MIN, "productive collections keep the minimum threshold");
}

fn noCollectWhileDraining() void {
    const n: usize = gc.THRESHOLD_MIN + 100;
    const nodes = allocNodes(n);
    defer std.heap.page_allocator.free(nodes);
    for (nodes, 0..) |*slot, i| slot.* = newObj(@intCast(i), 2);
    host.rt_rel_draining = 1;
    for (nodes) |o| objRel(o);
    check(gc.debugBuffered() == n, "no collection while rt_rel_draining");
    gc.rt_gc_collect();
    check(gc.debugBuffered() == n, "rt_gc_collect waits too");
    host.rt_rel_draining = 0;
    touch(nodes[0]);
    check(gc.debugBuffered() == n, "an already buffered object is not pushed again");
    const extra = newObj(-1, 2);
    objRel(extra);
    check(gc.debugBuffered() == 0, "the next new root collects");
    for (nodes) |o| check(isBlackUnbuffered(o) and countOf(o) == 1, "live objects kept");
    const f0 = objs_freed;
    for (nodes) |o| objRel(o);
    objRel(extra);
    check(objs_freed == f0 + @as(i64, @intCast(n)) + 1, "unbuffered objects freed by RC");
}

/// A collection that frees little of what it traced (roots inside one big live ring) doubles the threshold up to
/// THRESHOLD_MAX; a productive one resets it.
fn thresholdGrowth() void {
    // start from the minimum: one productive collection
    const s0 = newObj(0, 1);
    linkA(s0, s0);
    objRel(s0);
    collect();
    check(gc.debugThreshold() == gc.THRESHOLD_MIN, "minimum after a productive collection");
    const n = 3 * gc.THRESHOLD_MIN;
    const nodes = allocNodes(n);
    defer std.heap.page_allocator.free(nodes);
    for (nodes, 0..) |*slot, i| slot.* = newObj(@intCast(i), 0);
    for (nodes, 0..) |o, i| linkA(o, nodes[(i + 1) % n]);
    nodes[0].hdr += 1;
    for (nodes[0..gc.THRESHOLD_MIN]) |o| touch(o);
    check(gc.debugBuffered() == 0 and gc.debugThreshold() == 2 * gc.THRESHOLD_MIN, "unproductive collection doubles the threshold");
    var expect: u32 = 2 * gc.THRESHOLD_MIN;
    var i: usize = 0;
    while (i < 8) : (i += 1) {
        touch(nodes[i]);
        collect();
        expect = @min(expect * 2, gc.THRESHOLD_MAX);
        check(gc.debugThreshold() == expect, "doubles up to the maximum");
    }
    check(gc.debugThreshold() == gc.THRESHOLD_MAX, "maximum reached");
    for (0..100) |_| {
        const s = newObj(0, 1);
        linkA(s, s);
        objRel(s);
    }
    collect();
    check(gc.debugThreshold() == gc.THRESHOLD_MIN, "productive collection resets the threshold");
    for (nodes) |o| check(countOf(o) == 1 + @as(u64, if (o == nodes[0]) 1 else 0) and isBlackUnbuffered(o), "big live ring intact");
    const f0 = objs_freed;
    objRel(nodes[0]);
    collect();
    check(objs_freed == f0 + @as(i64, @intCast(n)), "big ring collected");
}

pub fn main() u8 {
    std.debug.print("gc={s}\n", .{std.mem.span(gc.rt_gc_name())});
    traversal(4096);
    allocAndStorage();
    acyclicAndShared();
    shapes();
    partialGarbage();
    deepChain(1_000_000);
    bufferedThenFreed();
    bufferedThenResurrected();
    releasesDuringFree();
    ccDeadAndHint();
    manyRoots();
    noCollectWhileDraining();
    thresholdGrowth();
    collect();
    check(objs_live == 0 and pays_live == 0, "every object freed");
    if (fails != 0) {
        std.debug.print("SUITE FAILED ({d})\n", .{fails});
        return 1;
    }
    std.debug.print("SUITE OK\n", .{});
    return 0;
}

export fn rt_leak_obj(delta: i64) callconv(.c) void {
    _ = delta;
}
