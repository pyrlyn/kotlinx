//! Vendored GC build. Adding a collector implementation needs no umbrella edit:
//! the Zig path dependency exports and wires all supported modules itself.
const std = @import("std");

pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{});
    const optimize = b.standardOptimizeOption(.{});
    const gcdep = b.dependency("kxgc_zig", .{ .target = target, .optimize = optimize });
    const kxgc_c_tc = b.addTranslateC(.{ .root_source_file = b.path("smoke/kxgc_c_root.h"), .target = target, .optimize = optimize, .link_libc = true });
    kxgc_c_tc.addIncludePath(b.path("c"));
    const kxgc_c = kxgc_c_tc.addModule("kxgc_c");

    const zig_smoke = b.addExecutable(.{ .name = "zig-epoch-smoke", .root_module = b.createModule(.{ .root_source_file = b.path("smoke/zig_epoch_smoke.zig"), .target = target, .optimize = optimize, .link_libc = true, .imports = &.{
        .{ .name = "kxgc", .module = gcdep.module("kxgc") },
        .{ .name = "host", .module = gcdep.module("host") },
        .{ .name = "epoch", .module = gcdep.module("epoch") },
    } }) });
    // An explicit configure hook links libc when an external package exports its own library.
    const adapter_opts = b.addOptions();
    adapter_opts.addOption(bool, "leakcheck", false);
    adapter_opts.addOption(bool, "pool", false);
    adapter_opts.addOption(bool, "plain_malloc", false);
    const host_opts = b.createModule(.{ .root_source_file = b.path("zig/src/lib/host_options.zig") });
    host_opts.addImport("gc_options", adapter_opts.createModule());
    const leak_adapter = b.createModule(.{ .root_source_file = b.path("zig/src/lib/leakcheck.zig") });
    leak_adapter.addImport("host_options", host_opts);
    for ([_]*std.Build.Module{ gcdep.module("host"), gcdep.module("rc"), gcdep.module("perceus"), gcdep.module("incremental"), gcdep.module("epoch") }) |m| {
        m.addImport("leakcheck", leak_adapter); m.addImport("host_options", host_opts);
    }
    b.installArtifact(zig_smoke);
    const run_zig = b.addRunArtifact(zig_smoke); run_zig.has_side_effects = true;
    const zig_step = b.step("zig-smoke", "Run Zig epoch smoke"); zig_step.dependOn(&run_zig.step);

    const runtime_c = b.path("../../compiler/runtime/runtime.c");
    const c_flags = [_][]const u8{ "-std=c11", "-DRT_PLAIN_MALLOC", "-Wno-unused-parameter" };
    const c_smoke = b.addExecutable(.{ .name = "c-epoch-smoke", .root_module = b.createModule(.{ .root_source_file = b.path("smoke/c_entry.zig"), .target = target, .optimize = optimize, .link_libc = true, .imports = &.{.{ .name = "kxgc_c", .module = kxgc_c }} }) });
    c_smoke.root_module.addCSourceFile(.{ .file = b.path("smoke/c_epoch_smoke.c"), .flags = &c_flags });
    c_smoke.root_module.addCSourceFile(.{ .file = b.path("c/epoch/gc.c"), .flags = &c_flags });
    c_smoke.root_module.addCSourceFile(.{ .file = runtime_c, .flags = &c_flags });
    c_smoke.root_module.addIncludePath(b.path("c"));
    b.installArtifact(c_smoke);
    const run_c = b.addRunArtifact(c_smoke); run_c.has_side_effects = true;
    const c_step = b.step("c-smoke", "Run C epoch smoke"); c_step.dependOn(&run_c.step);

    const zig_rt_smoke = b.addExecutable(.{
        .name = "zig-runtime-epoch-smoke",
        .root_module = b.createModule(.{ .root_source_file = b.path("smoke/zig_runtime_epoch_smoke.zig"), .target = target, .optimize = optimize, .link_libc = true, .imports = &.{.{ .name = "kxgc_c", .module = kxgc_c} } }),
    });
    zig_rt_smoke.root_module.addCSourceFile(.{ .file = runtime_c, .flags = &c_flags });
    zig_rt_smoke.root_module.addIncludePath(b.path("c"));
    zig_rt_smoke.root_module.addObjectFile(b.path("zig/zig-out/lib/libgc-epoch.a"));
    b.installArtifact(zig_rt_smoke);
    const run_zrt = b.addRunArtifact(zig_rt_smoke); run_zrt.has_side_effects = true;
    const zrt_step = b.step("zig-runtime-smoke", "Link Zig archive with runtime.c and check its C ABI");
    zrt_step.dependOn(&run_zrt.step);
    const smoke = b.step("smoke", "Run C, Zig module and Zig C ABI runtime integration");
    smoke.dependOn(c_step); smoke.dependOn(zig_step); smoke.dependOn(zrt_step);
}
