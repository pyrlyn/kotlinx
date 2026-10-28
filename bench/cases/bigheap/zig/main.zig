// bigheap: same workload as Kotlin. Idiomatic Zig: graphs, nodes, payloads and leaves created/destroyed
// explicitly with the C allocator (like the other cases); a replaced ring is freed by walking it once.
const std = @import("std");
const c = std.c;
const gpa = std.heap.c_allocator;
const Payload = struct { v: i64 };
const Graph = struct { id: i64, head: ?*Node };
const Node = struct { id: i64, next: ?*Node, owner: *Graph, p: *Payload };
const Leaf = struct { v: i64, next: ?*Leaf };
fn mk(id: i64, g: *Graph, v: i64) !*Node {
    const p = try gpa.create(Payload);
    p.* = .{ .v = v };
    const n = try gpa.create(Node);
    n.* = .{ .id = id, .next = null, .owner = g, .p = p };
    return n;
}
fn build(seed: i64, size: i64) !*Graph {
    const g = try gpa.create(Graph);
    g.* = .{ .id = seed, .head = null };
    const first = try mk(seed * 1000, g, seed * 3);
    var cur = first;
    var i: i64 = 1;
    while (i < size) : (i += 1) {
        const n = try mk(seed * 1000 + i, g, seed * 3 + i);
        cur.next = n;
        cur = n;
    }
    cur.next = first;
    g.head = first;
    return g;
}
fn walk(g: *Graph, steps: i32) i64 {
    var s: i64 = 0;
    var cur = g.head;
    var i: i32 = 0;
    while (i < steps) : (i += 1) {
        const n = cur orelse return s;
        s += n.id + n.p.v + n.owner.id;
        cur = n.next;
    }
    return s;
}
fn free(g: *Graph) void {
    const first = g.head.?;
    var cur = first.next;
    while (cur) |n| {
        if (n == first) break;
        cur = n.next;
        gpa.destroy(n.p);
        gpa.destroy(n);
    }
    gpa.destroy(first.p);
    gpa.destroy(first);
    gpa.destroy(g);
}
fn acyclic(seed: i64, n: i64) !i64 {
    var l: ?*Leaf = null;
    var i: i64 = 0;
    while (i < n) : (i += 1) {
        const e = try gpa.create(Leaf);
        e.* = .{ .v = seed + i, .next = l };
        l = e;
    }
    var s: i64 = 0;
    while (l) |e| { s += e.v; l = e.next; gpa.destroy(e); }
    return s;
}
pub fn main() !void {
    var info: c.mach_timebase_info_data = undefined;
    _ = c.mach_timebase_info(&info);
    const t0 = c.mach_absolute_time();
    const count = 2000;
    const graphs = try gpa.alloc(*Graph, count);
    for (graphs, 0..) |*g, k| g.* = try build(@intCast(k), 200);
    var x: i64 = 12345;
    var checksum: i64 = 0;
    var seed: i64 = count;
    var round: usize = 0;
    while (round < 200) : (round += 1) {
        var k: usize = 0;
        while (k < 50) : (k += 1) {
            x = x *% 6364136223846793005 +% 1442695040888963407;
            const idx: usize = @intCast((@as(u64, @bitCast(x)) >> 33) % count);
            const g = try build(seed, 200);
            free(graphs[idx]);
            graphs[idx] = g;
            checksum += walk(g, 50);
            checksum += try acyclic(seed, 100);
            seed += 1;
        }
    }
    for (graphs) |g| checksum += walk(g, 10);
    for (graphs) |g| free(g);
    gpa.free(graphs);
    const t1 = c.mach_absolute_time();
    std.debug.print("checksum={d} nanos={d}\n", .{ checksum, (t1 - t0) * info.numer / info.denom });
}
