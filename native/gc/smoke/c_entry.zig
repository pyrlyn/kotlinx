//! Zig entry for the C epoch smoke. Verifies `@import("kxgc_c")` (translate-c of kxgc.h).
const std = @import("std");
const kxgc_c = @import("kxgc_c");

extern fn c_epoch_smoke_main() c_int;

pub fn main() u8 {
    // translate-c exposes the preprocessor macro as a constant.
    if (kxgc_c.KXGC_API_VERSION != 1) {
        std.debug.print("C-SMOKE FAILED: KXGC_API_VERSION={d}\n", .{kxgc_c.KXGC_API_VERSION});
        return 1;
    }
    if (@sizeOf(kxgc_c.KxGcStats) != 32) {
        std.debug.print("C-SMOKE FAILED: KxGcStats size={d}\n", .{@sizeOf(kxgc_c.KxGcStats)});
        return 1;
    }
    return @intCast(c_epoch_smoke_main());
}
