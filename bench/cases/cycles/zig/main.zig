// cycles: 20k rings of 100 nodes (each with a heap payload); walk 150 steps; free every node by hand.
const std = @import("std");
const c = std.c;
const Payload = struct { v: i64 };
const Node = struct { id: i64, next: ?*Node, p: *Payload };
const gpa = std.heap.c_allocator;
fn node(id: i64) !*Node {
    const p = try gpa.create(Payload);
    p.* = .{ .v = id * 3 };
    const n = try gpa.create(Node);
    n.* = .{ .id = id, .next = null, .p = p };
    return n;
}
fn ring(seed: i64, size: i64) !*Node {
    const first = try node(seed);
    var cur = first;
    var i: i64 = 1;
    while (i < size) : (i += 1) { const n = try node(seed + i); cur.next = n; cur = n; }
    cur.next = first;
    return first;
}
fn walk(first: *Node, steps: i32) i64 {
    var s: i64 = 0;
    var cur: ?*Node = first;
    var i: i32 = 0;
    while (i < steps) : (i += 1) { const n = cur orelse return s; s += n.id + n.p.v; cur = n.next; }
    return s;
}
fn freeRing(first: *Node) void {
    var cur = first.next.?;
    while (cur != first) { const nx = cur.next.?; gpa.destroy(cur.p); gpa.destroy(cur); cur = nx; }
    gpa.destroy(first.p);
    gpa.destroy(first);
}
pub fn main() !void {
    var info: c.mach_timebase_info_data = undefined;
    _ = c.mach_timebase_info(&info);
    const t0 = c.mach_absolute_time();
    var checksum: i64 = 0;
    var r: i64 = 0;
    while (r < 20_000) : (r += 1) { const first = try ring(r, 100); checksum += walk(first, 150); freeRing(first); }
    const t1 = c.mach_absolute_time();
    std.debug.print("checksum={d} nanos={d}\n", .{ checksum, (t1 - t0) * info.numer / info.denom });
}
