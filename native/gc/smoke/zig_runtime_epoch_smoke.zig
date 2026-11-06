//! Smoke: KotlinX runtime.c + Zig libgc-epoch.a, API via `@import("kxgc_c")` (translate-c).
const std = @import("std");
const kxgc_c = @import("kxgc_c");

pub fn main() u8 {
    if (kxgc_c.KXGC_API_VERSION != 1) {
        std.debug.print("ZIG-RT-SMOKE FAILED: API {d}\n", .{kxgc_c.KXGC_API_VERSION});
        return 1;
    }
    const name = kxgc_c.rt_gc_name();
    std.debug.print("zig-rt-smoke gc={s}\n", .{std.mem.span(name)});
    var i: i32 = 0;
    while (i < 1000) : (i += 1) {
        const p: ?*anyopaque = kxgc_c.rt_alloc(48);
        if (p == null) return 1;
        const slots: [*]i64 = @ptrCast(@alignCast(p.?));
        slots[5] = i;
        if (slots[5] != i) return 1;
        kxgc_c.rt_free(p, 48);
    }
    var st: kxgc_c.KxGcStats = std.mem.zeroes(kxgc_c.KxGcStats);
    kxgc_c.rt_gc_stats(&st);
    std.debug.print("collections={d}\n", .{st.collections});
    std.debug.print("ZIG-RT-SMOKE OK\n", .{});
    return 0;
}
