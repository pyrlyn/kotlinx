// file: read data/words.txt, count lines and words, checksum h = h*31 + byte (wrapping i64).
const std = @import("std");
const c = std.c;
const FILE = opaque {};
extern "c" fn fopen(path: [*:0]const u8, mode: [*:0]const u8) ?*FILE;
extern "c" fn fseek(f: *FILE, off: c_long, whence: c_int) c_int;
extern "c" fn ftell(f: *FILE) c_long;
extern "c" fn fread(ptr: [*]u8, size: usize, n: usize, f: *FILE) usize;
extern "c" fn fclose(f: *FILE) c_int;
pub fn main() !void {
    var info: c.mach_timebase_info_data = undefined;
    _ = c.mach_timebase_info(&info);
    const path: [*:0]const u8 = if (c.getenv("BENCH_DATA")) |p| p else "data/words.txt";
    const t0 = c.mach_absolute_time();
    const f = fopen(path, "rb") orelse return error.OpenFailed;
    _ = fseek(f, 0, 2);
    const size: usize = @intCast(ftell(f));
    _ = fseek(f, 0, 0);
    const buf = try std.heap.c_allocator.alloc(u8, size);
    defer std.heap.c_allocator.free(buf);
    if (fread(buf.ptr, 1, size, f) != size) return error.ReadFailed;
    _ = fclose(f);
    var lines: i64 = 0;
    var words: i64 = 0;
    var h: i64 = 0;
    var inw = false;
    for (buf) |b| {
        h = h *% 31 +% @as(i64, b);
        if (b == '\n') lines += 1;
        const ws = b == ' ' or b == '\n';
        if (!ws and !inw) words += 1;
        inw = !ws;
    }
    const checksum = h +% lines *% 1_000_003 +% words;
    const t1 = c.mach_absolute_time();
    std.debug.print("checksum={d} lines={d} words={d} nanos={d}\n", .{ checksum, lines, words, (t1 - t0) * info.numer / info.denom });
}
