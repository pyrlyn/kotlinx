//! Optional C runtime object-accounting hook. Enabled by -Dleakcheck.
pub const enabled = @import("host_options").leakcheck;
extern fn rt_leak_obj(delta: i64) void;
pub inline fn add(delta: i64) void {
    if (enabled) rt_leak_obj(delta);
}
