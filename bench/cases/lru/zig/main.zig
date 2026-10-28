// lru: a real-world cyclic structure - an LRU cache (doubly linked list + key index), same workload as Kotlin.
// Idiomatic Zig: entries and values created/destroyed explicitly with the C allocator (like the other cases).
const std = @import("std");
const c = std.c;
const gpa = std.heap.c_allocator;
const Value = struct { v: i64 };
const Entry = struct { key: usize, value: *Value, prev: ?*Entry, next: ?*Entry };
const Lru = struct {
    capacity: usize,
    index: []?*Entry,
    head: ?*Entry = null,
    tail: ?*Entry = null,
    size: usize = 0,
    fn unlink(self: *Lru, e: *Entry) void {
        if (e.prev) |p| p.next = e.next else self.head = e.next;
        if (e.next) |n| n.prev = e.prev else self.tail = e.prev;
    }
    fn pushFront(self: *Lru, e: *Entry) void {
        e.prev = null;
        e.next = self.head;
        if (self.head) |h| h.prev = e else self.tail = e;
        self.head = e;
    }
    fn get(self: *Lru, key: usize) !i64 {
        if (self.index[key]) |e| {
            if (e.prev != null) { self.unlink(e); self.pushFront(e); }
            return e.value.v;
        }
        const v = try gpa.create(Value);
        v.* = .{ .v = @as(i64, @intCast(key)) * 7 + 1 };
        const e = try gpa.create(Entry);
        e.* = .{ .key = key, .value = v, .prev = null, .next = null };
        self.index[key] = e;
        self.pushFront(e);
        self.size += 1;
        if (self.size > self.capacity) {
            const t = self.tail.?;
            self.unlink(t);
            self.index[t.key] = null;
            self.size -= 1;
            gpa.destroy(t.value);
            gpa.destroy(t);
        }
        return -1;
    }
    fn deinit(self: *Lru) void {
        var cur = self.head;
        while (cur) |e| { cur = e.next; gpa.destroy(e.value); gpa.destroy(e); }
        gpa.free(self.index);
    }
};
pub fn main() !void {
    var info: c.mach_timebase_info_data = undefined;
    _ = c.mach_timebase_info(&info);
    const t0 = c.mach_absolute_time();
    const keys = 200_000;
    const index = try gpa.alloc(?*Entry, keys);
    @memset(index, null);
    var cache = Lru{ .capacity = 50_000, .index = index };
    var x: i64 = 12345;
    var hits: i64 = 0;
    var sum: i64 = 0;
    var i: usize = 0;
    while (i < 3_000_000) : (i += 1) {
        x = x *% 6364136223846793005 +% 1442695040888963407;
        const r: usize = @intCast((@as(u64, @bitCast(x)) >> 33) % 1_000_000);
        const key = if (r < 800_000) r % 40_000 else 40_000 + r % 160_000;
        const v = try cache.get(key);
        if (v >= 0) { hits += 1; sum += v; }
    }
    const checksum = sum + hits * 31 + @as(i64, @intCast(cache.size));
    cache.deinit();
    const t1 = c.mach_absolute_time();
    std.debug.print("checksum={d} nanos={d}\n", .{ checksum, (t1 - t0) * info.numer / info.denom });
}
