// nbody: 5 bodies, 1M steps of pairwise gravity in f64; checksum = (energy * 1e9) as i64.
const std = @import("std");
const c = std.c;
fn nowNs(info: c.mach_timebase_info_data, t0: u64) u64 {
    return (c.mach_absolute_time() - t0) * info.numer / info.denom;
}
const Body = struct { x: f64, y: f64, z: f64, vx: f64, vy: f64, vz: f64, m: f64 };
fn advance(bs: []*Body, dt: f64) void {
    const n = bs.len;
    for (0..n) |i| {
        const a = bs[i];
        for (i + 1..n) |j| {
            const b = bs[j];
            const dx = a.x - b.x;
            const dy = a.y - b.y;
            const dz = a.z - b.z;
            const d2 = dx * dx + dy * dy + dz * dz;
            const mag = dt / (d2 * @sqrt(d2));
            a.vx -= dx * b.m * mag;
            a.vy -= dy * b.m * mag;
            a.vz -= dz * b.m * mag;
            b.vx += dx * a.m * mag;
            b.vy += dy * a.m * mag;
            b.vz += dz * a.m * mag;
        }
    }
    for (bs) |b| {
        b.x += dt * b.vx;
        b.y += dt * b.vy;
        b.z += dt * b.vz;
    }
}
fn energy(bs: []*Body) f64 {
    var e: f64 = 0.0;
    const n = bs.len;
    for (0..n) |i| {
        const a = bs[i];
        e += 0.5 * a.m * (a.vx * a.vx + a.vy * a.vy + a.vz * a.vz);
        for (i + 1..n) |j| {
            const b = bs[j];
            const dx = a.x - b.x;
            const dy = a.y - b.y;
            const dz = a.z - b.z;
            e -= a.m * b.m / @sqrt(dx * dx + dy * dy + dz * dz);
        }
    }
    return e;
}
pub fn main() !void {
    var info: c.mach_timebase_info_data = undefined;
    _ = c.mach_timebase_info(&info);
    const gpa = std.heap.c_allocator;
    var bs: [5]*Body = undefined;
    for (0..5) |iu| {
        const i: i32 = @intCast(iu);
        const d: f64 = @floatFromInt(i);
        bs[iu] = try gpa.create(Body);
        bs[iu].* = .{ .x = d * 3.0, .y = @as(f64, @floatFromInt(@mod(i * 2, 5))) * 1.5, .z = @as(f64, @floatFromInt(@mod(i, 3))) - 1.0, .vx = 0.01 * d, .vy = -0.02 * d, .vz = 0.005, .m = 1.0 + d };
    }
    const t0 = c.mach_absolute_time();
    for (0..1_000_000) |_| advance(&bs, 0.001);
    const checksum: i64 = @intFromFloat(energy(&bs) * 1e9);
    std.debug.print("checksum={d} nanos={d}\n", .{ checksum, nowNs(info, t0) });
}
