//! Scoped Zig smoke for the vendored native epoch collector (cf79057).
//! Uses explicit modules: kxgc (ABI), host (runtime-symbol adapter), epoch (collector).
//! Separate process from the C smoke so host exports are not duplicated with runtime.c.
const std = @import("std");
const kxgc = @import("kxgc");
const host = @import("host");
const collector = @import("epoch");

pub fn main() !void {
    _ = host;
    _ = kxgc;
    const name = collector.rt_gc_name();
    std.debug.print("zig-smoke gc={s}\n", .{name});

    var i: i32 = 0;
    while (i < 10000) : (i += 1) {
        const raw = collector.rt_alloc(48) orelse {
            std.debug.print("ZIG-SMOKE FAILED: rt_alloc null\n", .{});
            std.process.exit(1);
        };
        const p: [*]i64 = @ptrCast(@alignCast(raw));
        p[0] = 1;
        p[5] = i;
        if (p[5] != i) {
            std.debug.print("ZIG-SMOKE FAILED: slot mismatch\n", .{});
            std.process.exit(1);
        }
        collector.rt_free(raw, 48);
    }

    // One traced object with a real VTab freemem path (same pointer layout as runtime).
    const Obj = extern struct {
        hdr: u64,
        vt: *kxgc.VTab,
        val: i64,
    };
    var freed: i64 = 0;
    const freemem = struct {
        var count: *i64 = undefined;
        fn f(p: ?*anyopaque) callconv(.c) void {
            count.* += 1;
            collector.rt_free_trace(p, @sizeOf(Obj));
        }
    };
    freemem.count = &freed;
    const rel = struct {
        fn f(p: ?*anyopaque) callconv(.c) void {
            const o: *Obj = @ptrCast(@alignCast(p orelse return));
            const c1 = o.hdr -% 1;
            if (c1 & kxgc.CNT_MASK != 0) {
                o.hdr = c1;
                return;
            }
            o.hdr = c1;
            if (o.vt.relo) |relo| relo(p);
            if (o.vt.freemem) |fm| fm(p);
        }
    }.f;
    var vt: kxgc.VTab = .{
        .rel = rel,
        .trav = null,
        .relo = null,
        .freemem = freemem.f,
        .cyclic = null,
    };
    const raw = collector.rt_alloc_trace(@sizeOf(Obj)) orelse {
        std.debug.print("ZIG-SMOKE FAILED: rt_alloc_trace null\n", .{});
        std.process.exit(1);
    };
    const o: *Obj = @ptrCast(@alignCast(raw));
    o.* = .{ .hdr = 1, .vt = &vt, .val = 42 };
    rel(o);
    if (freed != 1) {
        std.debug.print("ZIG-SMOKE FAILED: freed={d}\n", .{freed});
        std.process.exit(1);
    }
    std.debug.print("ZIG-SMOKE OK\n", .{});
}
