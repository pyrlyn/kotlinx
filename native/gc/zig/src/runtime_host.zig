//! KotlinX host: runtime callbacks come from runtime.c. This module is never
//! linked together with the test bench host, which exports those callbacks.
const c = @import("std").c;
pub const oom = @import("lib/host_util.zig").oom;
pub const malloc = @import("lib/host_util.zig").malloc;
pub const calloc = @import("lib/host_util.zig").calloc;
pub const realloc = @import("lib/host_util.zig").realloc;
pub const free = @import("lib/host_util.zig").free;
pub const getenv_i64 = @import("lib/host_util.zig").getenv_i64;
pub const getenv_present = @import("lib/host_util.zig").getenv_present;
pub const peakRssKb = @import("lib/host_util.zig").peakRssKb;

pub extern var rt_rel_draining: c_int;
pub extern var rt_relo_owner: c_int;
pub extern threadlocal var rt_trav_mode: c_int;
pub extern fn rt_nanos() i64;
pub extern fn rt_panic(msg: [*:0]const u8) noreturn;
pub extern fn rt_gc_note_pause(pause_ns: i64) void;
