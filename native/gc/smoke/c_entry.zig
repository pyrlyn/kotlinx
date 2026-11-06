//! Zig entry for the C epoch smoke executable. C provides `main`.
//! This file exists so `zig build` can own the link of runtime.c + epoch/gc.c + smoke.
const std = @import("std");

// C translation unit defines main; Zig root must still provide a start.
// We re-export by calling the C smoke from Zig main instead: rename C entry.
extern fn c_epoch_smoke_main() c_int;

pub fn main() u8 {
    const rc = c_epoch_smoke_main();
    return @intCast(rc);
}
